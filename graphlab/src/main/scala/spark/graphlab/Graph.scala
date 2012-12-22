package spark.GraphLab


import scala.math._
import spark.SparkContext
import spark.SparkContext._
import spark.HashPartitioner
import spark.storage.StorageLevel
import spark.KryoRegistrator
import spark.ClosureCleaner

/**
 * Class containing the id and value of a vertex
 */
case class Vertex[VD](val id: Int, val data: VD);

/**
 * Class containing both vertices and the edge data associated with an edge
 */
case class Edge[VD, ED](val source: Vertex[VD], val target: Vertex[VD],
  val data: ED) {
  def other(vid: Int) = { if (source.id == vid) target else source; }
  def vertex(vid: Int) = { if (source.id == vid) source else target; }
} // end of class edge

object EdgeDirection extends Enumeration {
  val None = Value("None")
  val In = Value("In")
  val Out = Value("Out")
  val Both = Value("Both")
}

/**
 * A partitioner for tuples that only examines the first value in the tuple.
 */
class PidVidPartitioner(val numPartitions: Int = 4) extends spark.Partitioner {
  def getPartition(key: Any): Int = key match {
    case (pid: Int, vid: Int) => abs(pid) % numPartitions
    case _ => 0
  }
  // Todo: check if other partitions is same
  override def equals(other: Any) = other.isInstanceOf[PidVidPartitioner]
}

/**
 * A Graph RDD that supports computation on graphs.
 */
class Graph[VD: Manifest, ED: Manifest](
  val vertices: spark.RDD[(Int, VD)],
  val edges: spark.RDD[((Int, Int), ED)]) {

  /** 
   * The number of unique vertices in this graph
   */
  lazy val numVertices = vertices.count().toInt
 
  /**
   * The number of unique edges in this graph
   */
  lazy val numEdges = edges.count().toInt

  /** 
   * The internal partitioner used to split EDGES over machines.  
   * The vertices are then copied to all machines with edges
   * adjacent to that vertex
   */
  private val partitioner = new PidVidPartitioner(32)

  /**
   * Create a cached in memory copy of the graph.
   */
  def cache(): Graph[VD, ED] = {
    new Graph(vertices.cache(), edges.cache())
  }


  /**
   * The join edges and vertices function returns a table which joins
   * the vertex and edge data.
   */
  def joinEdgesAndVertices(
    vTable: spark.RDD[(Int, (VD, Boolean))],
    eTable: spark.RDD[((Int, Int), (Int, ED))],
    vid2pid: spark.RDD[(Int, Int)]) = {
    
    // Split the table among machines
    val vreplicas = vTable.join(vid2pid).map {
      case (vid, ((vdata, active), pid)) => ((pid, vid), (vdata, active))
    }.partitionBy(partitioner).cache()
    

    eTable
      .join(vreplicas).mapPartitions( iter => iter.map {
        // Join with the source vertex data and rekey with target vertex
        case ((pid, source_id), ((target_id, edata), (source_vdata, source_active))) =>
          ((pid, target_id), (source_id, source_vdata, source_active, edata))
      })
      .join(vreplicas).mapPartitions(iter => iter.map {
        // Join with the target vertex and rekey back to the source vertex 
        case ((pid, target_id),
          ((source_id, source_vdata, source_active, edata),
            (target_vdata, target_active))) =>
          ((pid, source_id),
            (source_id, source_vdata, source_active, edata,
              target_id, target_vdata, target_active))
      })
//      .filter {
//        // Drop any edges that do not have active vertices
//        case ((pid, _),
//          (source_id, source_vdata, source_active, edata,
//            target_id, target_vdata, target_active)) =>
//          source_active || target_active
//      }
  } // end of join edges and vertices

  /**
   * Execute the synchronous powergraph abstraction.
   *
   * gather: (center_vid, edge) => (new edge data, accumulator)
   * Todo: Finish commenting
   */
  def iterateGAS[A: Manifest](
    gather: (Int, Edge[VD, ED]) => (ED, A),
    sum: (A, A) => A,
    default: A,
    apply: (Vertex[VD], A) => VD,
    scatter: (Int, Edge[VD, ED]) => (ED, Boolean),
    niter: Int,
    gatherEdges: EdgeDirection.Value = EdgeDirection.In,
    scatterEdges: EdgeDirection.Value = EdgeDirection.Out) = {

    ClosureCleaner.clean(gather)
    ClosureCleaner.clean(sum)
    ClosureCleaner.clean(apply)
    ClosureCleaner.clean(scatter)

    val numprocs = partitioner.numPartitions
    // Partition the edges over machines.  The part_edges table has the format
    // ((pid, source), (target, data))
    var eTable =
      edges.map {
        case ((source, target), data) => {
          // val pid = abs((source, target).hashCode()) % numprocs
          val pid = abs(source.hashCode()) % numprocs
          ((pid, source), (target, data))
        }
      }.partitionBy(partitioner).persist()

    // The master vertices are used during the apply phase   
    var vTable = vertices.map { case (vid, vdata) => (vid, (vdata, true)) }.persist()

    // Create a map from vertex id to the partitions that contain that vertex
    // (vid, pid)
    val vid2pid = eTable.flatMap {
      case ((pid, source), (target, _)) => Array((source, pid), (target, pid))
    }.distinct(1).persist() // what is the role of the number of bins?

    val replicationFactor = vid2pid.count().toDouble / vTable.count().toDouble
    println("Replication Factor: " + replicationFactor)

    // Loop until convergence or there are no active vertices
    var iter = 0
    var nactive = vTable.map {
      case (_, (_, active)) => if (active) 1 else 0
    }.reduce(_ + _);
    while (iter < niter && nactive > 0) {
      // Begin iteration    
      println("\n\n==========================================================")
      println("Begin iteration: " + iter)
      println("Active:          " + nactive)

      iter += 1

      // Gather Phase ---------------------------------------------
      val localGather = gather
      val localGatherEdges = gatherEdges

      // Compute the accumulator for each vertex
      val join1 = joinEdgesAndVertices(vTable, eTable, vid2pid).persist()
      println(join1.count)

      val gTable = join1.mapValues {
          case (sourceId, sourceVdata, sourceActive, edata,
            targetId, targetVdata, targetActive) => {
            val sourceVertex = new Vertex[VD](sourceId, sourceVdata)
            val targetVertex = new Vertex[VD](targetId, targetVdata)
            var newEdata = edata
            var targetAccum: Option[A] = None
            var sourceAccum: Option[A] = None
            if (targetActive && (localGatherEdges == EdgeDirection.In ||
              localGatherEdges == EdgeDirection.Both)) { // gather on the target
              val edge = new Edge(sourceVertex, targetVertex, newEdata);
              val (edataRet, accRet) = localGather(targetId, edge)
              targetAccum = Option(accRet)
              newEdata = edataRet
            }
            if (sourceActive && (localGatherEdges == EdgeDirection.Out ||
              localGatherEdges == EdgeDirection.Both)) { // gather on the source
              val edge = new Edge(sourceVertex, targetVertex, newEdata);
              val (edataRet, accRet) = localGather(sourceId, edge)
              sourceAccum = Option(accRet);
              newEdata = edataRet;
            }
            (sourceId, sourceAccum, targetId, targetAccum, newEdata)
          }
        }.cache()

      println(gTable.count())

      // update the edge data
      eTable = gTable.mapValues {
        case (sourceId, _, targetId, _, edata) => (targetId, edata)
      }
        
      // Compute the final sum
      val localDefault = default
      val localSum = sum
      val accum = gTable.flatMap {
        case (_, (sourceId, Some(sourceAccum), targetId, Some(targetAccum), _)) =>
          Array((sourceId, sourceAccum), (targetId, targetAccum))
        case (_, (sourceId, None, targetId, Some(targetAccum), _)) =>
          Array((targetId, targetAccum))
        case (_, (sourceId, Some(sourceAccum), targetId, None, _)) =>
          Array((sourceId, sourceAccum))
        case (_, (sourceId, None, targetId, None, _)) =>
          Array[(Int, A)]()
      }.reduceByKey(localSum)

      // Apply Phase ---------------------------------------------
      // Merge with the gather result

      val localApply = apply
      vTable = vTable.leftOuterJoin(accum).mapPartitions(
        iter => iter.map{ // Execute the apply if necessary
          case (vid, ((data, true), Some(accum))) =>
            (vid, (localApply(new Vertex[VD](vid, data), accum), true))
          case (vid, ((data, true), None)) =>
            (vid, (localApply(new Vertex[VD](vid, data), localDefault), true))
          case (vid, ((data, false), _)) => (vid, (data, false))
        }).cache()
      println(vTable.count())


      // Scatter Phase ---------------------------------------------
      val localScatter = scatter
      val localScatterEdges = scatterEdges
      val sTable = joinEdgesAndVertices(vTable, eTable, vid2pid).mapValues {
          case (sourceId, sourceVdata, sourceActive, edata,
            targetId, targetVdata, targetActive) => {
            val sourceVertex = new Vertex[VD](sourceId, sourceVdata)
            val targetVertex = new Vertex[VD](targetId, targetVdata)
            var newEdata = edata
            var newTargetActive = false
            var newSourceActive = false
            if (targetActive && (localScatterEdges == EdgeDirection.In ||
              localScatterEdges == EdgeDirection.Both)) { // scatter on the target
              val edge = new Edge(sourceVertex, targetVertex, newEdata);
              val result = localScatter(targetId, edge)
              newSourceActive = result._2
              newEdata = result._1
            }
            if (sourceActive && (localScatterEdges == EdgeDirection.Out ||
              localScatterEdges == EdgeDirection.Both)) { // scatter on the source
              val edge = new Edge(sourceVertex, targetVertex, newEdata);
              val result = localScatter(sourceId, edge)
              newTargetActive = result._2;
              newEdata = result._1;
            }
            (sourceId, newSourceActive, targetId, newTargetActive, newEdata)
          }
        }.cache()

      // update the edge data
      eTable = sTable.mapValues {
        case (sourceId, _, targetId, _, edata) => (targetId, edata)
      }

      val activeVertices = sTable.flatMap {
        case (_, (sourceId, sourceActive, targetId, targetActive, _)) =>
          Array((sourceId, sourceActive), (targetId, targetActive))
      }.reduceByKey(_ || _)


      // update active vertices
      vTable = vTable.leftOuterJoin(activeVertices).map {
        case (vid, ((vdata, _), Some(new_active))) => (vid, (vdata, new_active))
        case (vid, ((vdata, _), None)) => (vid, (vdata, false))
      }.cache()

      // Compute the number active
      nactive = vTable.map {
        case (_, (_, active)) => if (active) 1 else 0
      }.reduce(_ + _);
            
    }
    println("=========================================")
    println("Finished in " + iter + " iterations.")

    // Collapse vreplicas, edges and retuen a new graph
    new Graph(vTable.map { case (vid, (vdata, _)) => (vid, vdata) }, 
        eTable.map{ case ((pid, source), (target, edata)) => ((source, target), edata)} )
  } // End of iterate gas

} // End of Graph RDD

/**
 * Graph companion object
 */
object Graph {

  /**
   * Load an edge list from file initializing the Graph RDD
   */
  def load_graph[ED: Manifest](sc: SparkContext,
    fname: String, edge_parser: String => ED) = {

    val partitioner = new PidVidPartitioner()

    val edges = sc.textFile(fname).map(
      line => {
        val source :: target :: tail = line.split("\t").toList
        val edata = edge_parser(tail.mkString("\t"))
        ((source.trim.toInt, target.trim.toInt), edata)
      }).partitionBy(partitioner).cache() //persist(StorageLevel.DISK_ONLY)

    val vertices = edges.flatMap {
      case ((source, target), _) => List((source, 1), (target, 1))
    }.reduceByKey(_ + _).cache()

    val graph = new Graph[Int, ED](vertices, edges)

    println("Loaded graph:" +
      "\n\t#edges:    " + graph.numEdges +
      "\n\t#vertices: " + graph.numVertices)
    graph
  }
} // End of Graph Object

// class MyRegistrator extends KryoRegistrator {
//   override def registerClasses(kryo: Kryo) {
//     kryo.register(classOf[MyClass1])
//     kryo.register(classOf[MyClass2])
//   }
// }

/**
 * Test object for graph class
 */
object GraphTest {

  def toy_graph(sc: SparkContext) = {
    val edges = Array((1 -> 2, 0), (2 -> 3, 0), (3 -> 1, 0),
      (4 -> 5, 0), (5 -> 6, 0), (6 -> 7, 0), (7 -> 8, 0))

    val vertices = (1 to 8).map((_, 1))
    val graph = new Graph(sc.parallelize(vertices, 2), sc.parallelize(edges, 2)).cache
    graph
  }

  def load_file(sc: SparkContext, fname: String) = {
    val graph = Graph.load_graph(sc, fname, x => 0)
    graph
  }

  def test_pagerank(graph: Graph[Int, Int]) {
    val out_degree = graph.edges.map {
      case ((src, target), data) => (src, 1)
    }.reduceByKey(_ + _);
    val initial_vdata = graph.vertices.join(out_degree).map {
      case (vid, (out_degree, _)) => (vid, (out_degree, 1.0F, 1.0F));
    }
    val graph2 = new Graph(initial_vdata, graph.edges).cache()
    val graph_ret = graph2.iterateGAS(
      (me_id, edge) => {
        val Edge(Vertex(_, (out_degree, rank, _)), _, edata) = edge
        (edata, rank / out_degree)
      }, // gather
      (a: Float, b: Float) => a + b, // sum
      0F,
      (vertex, a: Float) => {
        val Vertex(vid, (out_degree, rank, old_rank)) = vertex
        (out_degree, (0.15F + 0.85F * a), rank)
      }, // apply
      (me_id, edge) => {
        val Edge(Vertex(_, (_, new_rank, old_rank)), _, edata) = edge
        (edata, abs(new_rank - old_rank) > 0.01)
      }, // scatter
      10).cache()
    println("Computed graph: #edges: " + graph_ret.numEdges + "  #vertices" + graph_ret.numVertices)
    graph_ret.vertices.take(10).foreach(println)
  }

  def test_connected_component(graph: Graph[Int, Int]) {
    val initial_ranks = graph.vertices.map { case (vid, _) => (vid, vid) }
    val graph2 = new Graph(initial_ranks, graph.edges).cache
    val niterations = 100;
    val graph_ret = graph2.iterateGAS(
      (me_id, edge) => (edge.data, edge.other(me_id).data), // gather
      (a: Int, b: Int) => min(a, b), // sum
      Integer.MAX_VALUE,
      (v, a: Int) => min(v.data, a), // apply
      (me_id, edge) => (edge.data + 1, 
          edge.other(me_id).data > edge.vertex(me_id).data), // scatter
      niterations,
      gatherEdges = EdgeDirection.Both,
      scatterEdges = EdgeDirection.Both).cache()
    graph_ret.vertices.collect.foreach(println)
    graph_ret.edges.take(10).foreach(println)
  }

  def main(args: Array[String]) {

    // System.setProperty("spark.serializer", "spark.KryoSerializer")
    // System.setProperty("spark.kryo.registrator", classOf[KryoRegistrator].getName)

    val filename = if (args.length > 0) args(0) else ""
    val spark_master = if (args.length > 1) args(1) else "local[4]"
    println("Spark Master: " + spark_master)
    println("Graph file:   " + filename)
    
    val jobname = "graphtest"
    //  val spark_home = "/home/jegonzal/local/spark"
    //  val graphlab_jar = List("/home/jegonzal/Documents/scala_graphlab/graphlab/target/scala-2.9.2/graphlab_2.9.2-1.0-spark.jar")
    //    val hdfs_path = "hdfs://128.2.204.196/users/jegonzal/google.tsv"
    var sc: SparkContext = null;
    try {
      sc = new SparkContext(spark_master, jobname) // , spark_home, graphlab_jar)
      val graph = if (!filename.isEmpty) load_file(sc, filename) else toy_graph(sc)
      test_connected_component(graph)
    } catch {
      case e: Throwable => println(e)
    }

    if (sc != null) sc.stop()

  }
} // end of GraphTest




