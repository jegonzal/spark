package org.apache.spark.mllib.optimization

import java.util.UUID
import java.util.concurrent._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import breeze.linalg.{DenseVector => BDV, SparseVector => BSV, Vector => BV}
import org.apache.spark.Logging
import org.apache.spark.deploy.worker.Worker
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import akka.routing.{BroadcastRouter, RoundRobinRouter, Router}
import java.util

//
//case class AsyncSubProblem(data: Array[(Double, Vector)], comm: WorkerCommunication)

object HWInternalMessages {
  class WakeupMsg
  class PingPong
  class DeltaUpdate(val sender: Int,
                    val delta: BV[Double])
}

class HWWorkerCommunicationHack {
  var ref: HWWorkerCommunication = null
}

class HWWorkerCommunication(val address: String, val hack: HWWorkerCommunicationHack) extends Actor with Logging {
  hack.ref = this
  val others = new mutable.HashMap[Int, ActorRef]
  var selfID: Int = -1

  var inputQueue = new LinkedBlockingQueue[HWInternalMessages.DeltaUpdate]()

  def receive = {
    case ppm: InternalMessages.PingPong => {
      logInfo("new message from " + sender)
    }
    case m: InternalMessages.WakeupMsg => {
      logInfo("activated local!"); sender ! "yo"
    }
    case s: String => println(s)
    case d: HWInternalMessages.DeltaUpdate => {
      inputQueue.add(d)
    }
    case _ => println("hello, world!")
  }

  def shuttingDown: Receive = {
    case _ => println("GOT SHUTDOWN!")
  }

  def connectToOthers(allHosts: Array[String]) {
    var i = 0
    //logInfo(s"Connecting to others ${allHosts.mkString(",")} ${allHosts.length}")
    for (host <- allHosts) {
      if (!host.equals(address)) {
        //logInfo(s"Connecting to $host, $i")
        val selection = context.actorSelection(allHosts(i))

        implicit val timeout = Timeout(150000 seconds)
        val f = selection.resolveOne()
        Await.ready(f, Duration.Inf)
        val ref = f.value.get.get
        others.put(i, ref)

        logInfo(s"Connected to ${f.value.get.get}")
      } else {
        selfID = i
      }
      i += 1
    }

    val routees = new util.ArrayList[ActorRef]
    for(other <- others.values) {
      routees.add(other)
    }
  }

  def sendPingPongs() {
    for (other <- others.values) {
      other ! new InternalMessages.PingPong
    }
  }

  def broadcastDeltaUpdate(delta: BV[Double]) {
    val msg = new HWInternalMessages.DeltaUpdate(selfID, delta)
    for(other <- others.values) {
      other ! msg
    }
  }
}



class HOGWILDSGDWorker(subProblemId: Int,
                      val nSubProblems: Int,
                      data: Array[(Double, BV[Double])],
                      primalVar0: BV[Double],
                      gradient: FastGradient,
                      val consensus: ConsensusFunction,
                      val regParam: Double,
                      eta_0: Double,
                      epsilon: Double,
                      maxIterations: Int,
                      miniBatchSize: Int,
                      rho: Double,
                      val comm: HWWorkerCommunication,
                      val broadcastDelayMS: Int)
  extends SGDLocalOptimizer(subProblemId = subProblemId, data = data, primalVar = primalVar0.copy,
    gradient = gradient, eta_0 = eta_0, epsilon = epsilon, maxIterations = maxIterations,
    miniBatchSize = miniBatchSize)
  with Logging {


  @volatile var done = false

  primalConsensus = primalVar0.copy
  var grad_delta: BV[Double] = BV.zeros(primalVar0.size)


  var msgsSent = 0
  var localIters = 0

  override def getStats() = {
    WorkerStats(primalVar = primalVar, dualVar = dualVar, 
      msgsSent = msgsSent, localIters = localIters, 
      dataSize = data.length)
  }


  val broadcastThread = new Thread {
    override def run {
      while (!done) {
        comm.broadcastDeltaUpdate(grad_delta)
        msgsSent += 1
        grad_delta = BV.zeros(primalVar0.size)
        Thread.sleep(broadcastDelayMS)
      }
    }
  }



  def mainLoop(runTimeMS: Int = 1000) = {
    done = false
    // Launch a thread to send the messages in the background
    broadcastThread.start()

    val startTime = System.currentTimeMillis()

    var t = 0
    // Loop until done
    while (!done) {
      // Reset the primal var

      var tiq = comm.inputQueue.poll()
      val receivedMsgs = tiq != null
      while (tiq != null) {
        primalVar += tiq.delta
        tiq = comm.inputQueue.poll()
      }

      var iter = 0
      residual = Double.MaxValue
      var currentTime = startTime
      while(iter < 10 && !done) {
        grad *= 0.0 // Clear the gradient sum
        var b = 0
        while (b < 1) {
          val ind = if (miniBatchSize < nExamples) rnd.nextInt(nExamples) else b
          gradient(primalVar, data(ind)._2, data(ind)._1, grad)
          b += 1
        }
        // Set the learning rate
        val eta_t = eta_0 / (t.toDouble + 1.0)
        grad *= eta_t

        grad_delta += grad
        primalVar += grad

        // Compute residual.
        //residual = eta_t * norm(grad, 2.0)

        if (t % 1000 == 0) {
          currentTime = System.currentTimeMillis()
        }

        iter += 1
        t += 1
      }
      // Check to see if we are done
      val elapsedTime = System.currentTimeMillis() - startTime
      done = elapsedTime > runTimeMS
    }
    localIters = t
    primalVar
  }

}


class HOGWILDSGD(val gradient: FastGradient, var consensus: ConsensusFunction) extends Optimizer with Serializable with Logging {

  var runtimeMS: Int = 5000
  var paramBroadcastPeriodMs = 100
  var regParam: Double = 1.0
  var epsilon: Double = 1.0e-5
  var eta_0: Double = 1.0
  var localEpsilon: Double = 0.001
  var localMaxIterations: Int = Integer.MAX_VALUE
  var miniBatchSize: Int = 10
  var displayLocalStats: Boolean = true
  var broadcastDelayMS: Int = 100
  var stats: WorkerStats = null
  var rho: Double = 1.0

  @transient var workers : RDD[HOGWILDSGDWorker] = null

  def setup(input: RDD[(Double, Vector)], primal0: BV[Double]) {
    val nSubProblems = input.partitions.length

    workers = input.mapPartitionsWithIndex { (ind, iter) =>
      val data: Array[(Double, BV[Double])] =
        iter.map { case (label, features) => (label, features.toBreeze)}.toArray
      val workerName = UUID.randomUUID().toString
      val address = Worker.HACKakkaHost+workerName
      val hack = new HWWorkerCommunicationHack()
      logInfo(s"local address is $address")
      val aref = Worker.HACKworkerActorSystem.actorOf(Props(new HWWorkerCommunication(address, hack)), workerName)
      implicit val timeout = Timeout(15 seconds)

      val f = aref ? new InternalMessages.WakeupMsg
      Await.result(f, timeout.duration).asInstanceOf[String]

      val worker = new HOGWILDSGDWorker(subProblemId = ind, nSubProblems = nSubProblems, data = data,
        primalVar0 = primal0.copy, gradient = gradient, consensus = consensus, regParam = regParam,
        eta_0 = eta_0, epsilon = localEpsilon, maxIterations = localMaxIterations,
        miniBatchSize = miniBatchSize, rho = rho, comm = hack.ref, broadcastDelayMS = broadcastDelayMS)

      Iterator(worker)
    }.cache()

    // collect the addresses
    val addresses = workers.map {
      if(SetupBlock.initialized) {
        throw new RuntimeException("Worker was evicted, dying lol!")
      }
      w => w.comm.address
    }.collect()

    // Establish connections to all other workers
    workers.foreach { w =>
      SetupBlock.initialized = true
      w.comm.connectToOthers(addresses)
    }

    // Ping Pong?  Just because?
    workers.foreach { w => w.comm.sendPingPongs() }
  }

  var totalTimeMs: Long = -1

  def optimize(input: RDD[(Double, Vector)], primal0: Vector): Vector = {
    // Initialize the cluster
    setup(input, primal0.toBreeze)


    val startTimeNs = System.nanoTime()

    // Run all the workers
    workers.foreach( w => w.mainLoop(runtimeMS) )
    // compute the final consensus value synchronously
    val nExamples = workers.map(w=>w.data.length).reduce(_+_)
    // Collect the primal and dual averages
    stats = 
      workers.map { w => w.getStats() }.reduce( _ + _ )

    val totalTimeNs = System.nanoTime() - startTimeNs
    totalTimeMs = TimeUnit.MILLISECONDS.convert(totalTimeNs, TimeUnit.NANOSECONDS)


    Vectors.fromBreeze(stats.primalAvg)
  }
}

