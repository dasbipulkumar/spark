package spark

import java.io._

import scala.collection.mutable.ArrayBuffer


class SparkContext(
  master: String,
  frameworkName: String,
  val sparkHome: String = null,
  val jars: Seq[String] = Nil)
extends Logging {
  private[spark] var scheduler: Scheduler = {
    // Regular expression used for local[N] master format
    val LOCAL_N_REGEX = """local\[([0-9]+)\]""".r
    master match {
      case "local" =>
        new LocalScheduler(1)
      case LOCAL_N_REGEX(threads) =>
        new LocalScheduler(threads.toInt)
      case _ =>
        System.loadLibrary("mesos")
        new MesosScheduler(this, master, frameworkName)
    }
  }

  private val isLocal = scheduler.isInstanceOf[LocalScheduler]

  // Start the scheduler and the broadcast system
  scheduler.start()
  Broadcast.initialize(true)

  // Methods for creating RDDs

  def parallelize[T: ClassManifest](seq: Seq[T], numSlices: Int): RDD[T] =
    new ParallelArray[T](this, seq, numSlices)

  def parallelize[T: ClassManifest](seq: Seq[T]): RDD[T] =
    parallelize(seq, scheduler.numCores)

  def textFile(path: String): RDD[String] =
    new HdfsTextFile(this, path)

  def union[T: ClassManifest](rdds: RDD[T]*): RDD[T] =
    new UnionRDD(this, rdds)

  // Methods for creating shared variables

  def accumulator[T](initialValue: T)(implicit param: AccumulatorParam[T]) =
    new Accumulator(initialValue, param)

  // TODO: Keep around a weak hash map of values to Cached versions?
  def broadcast[T](value: T) = new CentralizedHDFSBroadcast(value, isLocal)
  //def broadcast[T](value: T) = new ChainedStreamingBroadcast(value, isLocal)

  // Stop the SparkContext
  def stop() {
     scheduler.stop()
     scheduler = null
  }
  
  // Wait for the scheduler to be registered
  def waitForRegister() {
    scheduler.waitForRegister()
  }

  // Get Spark's home location from either a value set through the constructor,
  // or the spark.home Java property, or the SPARK_HOME environment variable
  // (in that order of preference). If neither of these is set, return None.
  def getSparkHome(): Option[String] = {
    if (sparkHome != null)
      Some(sparkHome)
    else if (System.getProperty("spark.home") != null)
      Some(System.getProperty("spark.home"))
    else if (System.getenv("SPARK_HOME") != null)
      Some(System.getenv("SPARK_HOME"))
    else
      None
  }

  // Submit an array of tasks (passed as functions) to the scheduler
  def runTasks[T: ClassManifest](tasks: Array[() => T]): Array[T] = {
    runTaskObjects(tasks.map(f => new FunctionTask(f)))
  }

  // Run an array of spark.Task objects
  private[spark] def runTaskObjects[T: ClassManifest](tasks: Seq[Task[T]])
      : Array[T] = {
    logInfo("Running " + tasks.length + " tasks in parallel")
    val start = System.nanoTime
    val result = scheduler.runTasks(tasks.toArray)
    logInfo("Tasks finished in " + (System.nanoTime - start) / 1e9 + " s")
    return result
  }
  
  // Clean a closure to make it ready to serialized and send to tasks
  // (removes unreferenced variables in $outer's, updates REPL variables)
  private[spark] def clean[F <: AnyRef](f: F): F = {
    ClosureCleaner.clean(f)
    return f
  }
}


/**
 * The SparkContext object contains a number of implicit conversions and
 * parameters for use with various Spark features.
 */
object SparkContext {
  implicit object DoubleAccumulatorParam extends AccumulatorParam[Double] {
    def addInPlace(t1: Double, t2: Double): Double = t1 + t2
    def zero(initialValue: Double) = 0.0
  }

  implicit object IntAccumulatorParam extends AccumulatorParam[Int] {
    def addInPlace(t1: Int, t2: Int): Int = t1 + t2
    def zero(initialValue: Int) = 0
  }

  // TODO: Add AccumulatorParams for other types, e.g. lists and strings

  implicit def rddToPairRDDExtras[K, V](rdd: RDD[(K, V)]) =
    new PairRDDExtras(rdd)
}
