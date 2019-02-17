package br.ufmg.cs.systems.fractal

import br.ufmg.cs.systems.fractal.aggregation._
import br.ufmg.cs.systems.fractal.aggregation.reductions._
import br.ufmg.cs.systems.fractal.computation._
import br.ufmg.cs.systems.fractal.conf.{Configuration, SparkConfiguration}
import br.ufmg.cs.systems.fractal.pattern.Pattern
import br.ufmg.cs.systems.fractal.subgraph._
import br.ufmg.cs.systems.fractal.util._
import com.koloboke.collect.IntCollection
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.Writable
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable.Map
import scala.reflect.{ClassTag, classTag}

/**
 * Results of an Arabesque computation.
 */
case class Fractoid [E <: Subgraph : ClassTag](
                                                arabGraph: FractalGraph,
                                                stepByStep: Boolean,
                                                mustSync: Boolean,
                                                scope: Int,
                                                step: Int,
                                                parentOpt: Option[Fractoid[E]],
                                                config: SparkConfiguration[E],
                                                aggFuncs: Map[String,(
      (E,Computation[E],_ <: Writable) => _ <: Writable,
      (E,Computation[E],_ <: Writable) => _ <: Writable
    )],
                                                storageLevel: StorageLevel) extends Logging {

  def this(arabGraph: FractalGraph, config: SparkConfiguration[E]) = {
    this(arabGraph, false, false, 0, 0,
      None, config, Map.empty, StorageLevel.NONE)
  }

  def sparkContext: SparkContext = arabGraph.arabContext.sparkContext

  if (!config.isInitialized) {
    config.initialize(isMaster = true)
  }

  /**
   * Size of the chain of computations in this result
   */
  lazy val numComputations: Int = {
    var currOpt = Option(getComputationContainer[E])
    var nc = 0
    while (currOpt.isDefined) {
      nc += 1
      currOpt = currOpt.get.nextComputationOpt.
        asInstanceOf[Option[ComputationContainer[E]]]
    }
    nc
  }

  /**
   * Number of computations accumulated, including parents. This value starts
   * with zero, thus, depth=0 implies in numComputations=1
   */
  lazy val depth: Int = {
    @scala.annotation.tailrec
    def findDepthRec(r: Fractoid[E], accum: Int): Int = {
      if (!r.parentOpt.isDefined) {
        accum + r.numComputations
      } else {
        findDepthRec(r.parentOpt.get, accum + r.numComputations)
      }
    }
    findDepthRec(this, -1)
  }

  /**
   * Mark this result to be persisted in memory only
   */
  def cache: Fractoid[E] = persist (StorageLevel.MEMORY_ONLY)

  /**
   * Mark this result to be persisted according to a specific persistence level
   */
  def persist(sl: StorageLevel): Fractoid[E] = {
    this.copy(storageLevel = sl)
  }

  /**
   * Unpersist this result
   */
  def unpersist: Fractoid[E] = persist(StorageLevel.NONE)

  /**
   * Check if this result is marked as persisted in some way
   */
  def isPersisted: Boolean = storageLevel != StorageLevel.NONE

  /**
   * Lazy evaluation for the results
   */
  private var masterEngineOpt: Option[SparkMasterEngine[E]] = None

  def masterEngine: SparkMasterEngine[E] = synchronized {
    masterEngineOpt match {
      case None =>

        var _masterEngine = parentOpt match {
          case Some(parent) =>
            if (parent.masterEngine.next) {
              SparkMasterEngine [E] (sparkContext, config, parent.masterEngine)
            } else {
              masterEngineOpt = Some(parent.masterEngine)
              return parent.masterEngine
            }

          case None =>
            SparkMasterEngine [E] (sparkContext, config)
        }

        _masterEngine.persist(storageLevel)

        assert (_masterEngine.superstep == this.step,
          s"masterEngineNext=${_masterEngine.next}" +
          s" masterEngineStep=${_masterEngine.superstep} thisStep=${this.step}")

        logInfo (s"Computing ${this}. Engine: ${_masterEngine}")
        _masterEngine = if (stepByStep) {
          _masterEngine.next
          _masterEngine
        } else {
          _masterEngine.compute
        }

        _masterEngine.finalizeComputation
        masterEngineOpt = Some(_masterEngine)
        _masterEngine

      case Some(_masterEngine) =>
        _masterEngine
    }
  }

  def compute(): Map[String,Long] = {
    masterEngine.aggAccums.map{case (k,v) => (k, v.value.longValue)}
  }

  /**
   * Output: Subgraphs
   */
  private var SubgraphsOpt: Option[RDD[ResultSubgraph[_]]] = None

  def subgraphs: RDD[ResultSubgraph[_]] = subgraphs((_, _) => true)

  def subgraphs(shouldOutput: (E,Computation[E]) => Boolean)
    : RDD[ResultSubgraph[_]] = {
    if (config.confs.contains(SparkConfiguration.COMPUTATION_CONTAINER)) {
      val thisWithOutput = withOutput(shouldOutput).set(
        "output_path",
        s"${config.getOutputPath}-${step}"
        )
      //thisWithOutput.config.setOutputPath(
      //  s"${thisWithOutput.config.getOutputPath}-${step}")

      logInfo (s"Output to get Subgraphs: ${this} ${thisWithOutput}")
      thisWithOutput.masterEngine.getSubgraphs
    } else {
      SubgraphsOpt match {
        case None if config.isOutputActive =>
          val _Subgraphs = masterEngine.getSubgraphs
          SubgraphsOpt = Some(_Subgraphs)
          _Subgraphs

        case Some(_Subgraphs) if config.isOutputActive =>
          _Subgraphs

        case _ =>
          masterEngineOpt = None
          SubgraphsOpt = None
          config.set ("output_active", true)
          subgraphs
      }
    }
  }

  def internalSubgraphs: RDD[E] = internalSubgraphs((_,_) => true)

  def internalSubgraphs(
      shouldOutput: (E,Computation[E]) => Boolean): RDD[E] = {
    if (config.confs.contains(SparkConfiguration.COMPUTATION_CONTAINER)) {
      val thisWithOutput = withOutput(shouldOutput).set(
        "output_path",
        s"${config.getOutputPath}-${step}"
        )
      logInfo (s"Output to get internalSubgraphs: ${this} ${thisWithOutput}")
      val configBc = thisWithOutput.masterEngine.configBc
      thisWithOutput.masterEngine.getSubgraphs.
        map (_.toInternalSubgraph [E] (configBc.value))
    } else {
      throw new RuntimeException(s"Not supported yet for internal Subgraphs")
    }
  }

  /**
   * Registered aggregations
   */
  def registeredAggregations: Array[String] = {
    config.getAggregationsMetadata.map (_._1).toArray
  }

  /**
   * Get aggregation mappings defined by the user or empty if it does not exist
   */
  def aggregation [K <: Writable, V <: Writable] (name: String,
      shouldAggregate: (E,Computation[E]) => Boolean): Map[K,V] = {
    withAggregation [K,V] (name, shouldAggregate).aggregation (name)
  }

  /**
   * Get aggregation mappings defined by the user or empty if it does not exist
   */
  def aggregation [K <: Writable, V <: Writable] (name: String): Map[K,V] = {
    val aggValue = aggregationStorage [K,V] (name)
    if (aggValue == null) Map.empty[K,V]
    else aggValue.getMapping
  }

  /**
   * Get aggregation mappings defined by the user or empty if it does not exist
   */
  def aggregationStorage [K <: Writable, V <: Writable] (name: String)
    : AggregationStorage[K,V] = {
    masterEngine.getAggregatedValue [AggregationStorage[K,V]] (name)
  }

  /*
   * Get aggregations defined by the user as an RDD or empty if it does not
   * exist.
   */
  def aggregationRDD [K <: Writable, V <: Writable] (name: String,
      shouldAggregate: (E,Computation[E]) => Boolean)
    : RDD[(SerializableWritable[K],SerializableWritable[V])] = {
    sparkContext.parallelize (aggregation [K,V] (name, shouldAggregate).toSeq.
    map {
      case (k,v) => (new SerializableWritable(k), new SerializableWritable(v))
    }, config.numPartitions)
  }

  /*
   * Get aggregations defined by the user as an RDD or empty if it does not
   * exist.
   */
  def aggregationRDD [K <: Writable, V <: Writable] (name: String)
    : RDD[(SerializableWritable[K],SerializableWritable[V])] = {
    sparkContext.parallelize (aggregation [K,V] (name).toSeq.map {
      case (k,v) => (new SerializableWritable(k), new SerializableWritable(v))
    }, config.numPartitions)
  }

  /**
   * Saves Subgraphs as sequence files (HDFS):
   * [[org.apache.hadoop.io.NullWritable, ResultSubgraph]]
   * Behavior:
   *  - If at this point no computation was performed we just configure
   *  the execution engine and force the computation(count action)
   *  - Otherwise we rename the Subgraphs path to *path* and clear the
   *  Subgraphs RDD variable, which will force the creation of a new RDD with
   *  the corrected path.
   *
   * @param path hdfs (hdfs://) or local (file://) path
   */
  def saveSubgraphsAsSequenceFile(path: String): Unit = SubgraphsOpt match {
    case None =>
      logInfo ("no emebeddings found, computing them ... ")
      config.setOutputPath (path)
      subgraphs.count

    case Some(_Subgraphs) =>
      logInfo (
        s"found results, renaming from ${config.getOutputPath} to ${path}")
      val fs = FileSystem.get(sparkContext.hadoopConfiguration)
      fs.rename (new Path(config.getOutputPath), new Path(path))
      if (config.getOutputPath != path) SubgraphsOpt = None
      config.setOutputPath (path)

  }

  /**
   * Saves the Subgraphs as text
   *
   * @param path hdfs(hdfs://) or local(file://) path
   */
  def saveSubgraphsAsTextFile(path: String): Unit = {
    subgraphs.
      map (emb => emb.words.mkString(" ")).
      saveAsTextFile (path)
  }

  /**
   * Explore steps undefinitely. It is only safe if the last computation has an
   * well defined stop condition
   */
  def exploreAll(): Fractoid[E] = {
    this.copy(stepByStep = false)
  }

  /**
   * Alias for exploring one single step
   */
  def explore: Fractoid[E] = {
    explore(1)
  }

  def exploreExp(n: Int): Fractoid[E] = {
    var currResult = this
    var results: List[Fractoid[E]] = List(currResult)
    var numResults = 1
    while (currResult.parentOpt.isDefined) {
      currResult = currResult.parentOpt.get
      results = currResult :: results
      numResults += 1
    }

    var i = 0
    var j = numResults
    currResult = this
    while (i < n) {
      results.foreach { r =>
        currResult = currResult.handleNextResult(r)
          //.copy(step = j)
        j += 1
      }
      i += 1
    }

    currResult
  }

  /**
   * Explore *numComputations* times the last computation
   *
   * @param numComputations how many times the last computation must be repeated
   *
   * @return new result
   * TODO: review this function, there are potential inconsistencies
   */
  def explore(numComputations: Int, stepLen: Int = 1): Fractoid[E] = {
    var results = new Array[Fractoid[E]](stepLen)
    var currResult = this
    for (i <- (stepLen - 1) to 0 by -1) {
      results(i) = currResult
      currResult = currResult.parentOpt.getOrElse(currResult)
    }
    currResult = this

    // we support forward and backward exploration
    if (numComputations > 0) {
      // forward target represents how many computations we should append
      // target is handled differently at depth 0 because we want the first
      // *explore* to reach the level of Subgraphs from an empty state, i.e.,
      // an empty subgraph.
      val target = numComputations

      for (i <- 0 until target) if (results(0).mustSync) {
        // in case of this result cannot be pipelined
        val newScope = currResult.scope + 1
        for (i <- 0 until results.length) {
          currResult = results(i).copy (scope = newScope,
            step = currResult.step + 1,
            parentOpt = Some(currResult),
            storageLevel = StorageLevel.NONE)
          logInfo (s"Synchronization included after ${results(i)}." +
            s" Result: ${currResult}")
        }
      } else if (stepLen <= 1) {
        // in case of this result can be pipelined with the previous computation
        currResult = currResult.withNextComputation (
          getComputationContainer[E].lastComputation
        )
        logInfo (s"Computation appended to ${this}. Result: ${currResult}")
      } else {
        currResult = currResult.withNextComputation (
          results(0).getComputationContainer[E]
        )
        for (i <- 1 until results.length) {
            currResult = results(i).copy (
              step = currResult.step + 1,
              parentOpt = Some(currResult),
              storageLevel = StorageLevel.NONE)
              logInfo (s"Synchronization included after ${results(i)}." +
                s" Result: ${currResult}")
        }
      }
      logInfo (s"Forward exploration from ${this} to ${currResult}")

    } else if (numComputations < 0) {
      // backward target here represents the depth we want to reach
      val target = (depth + numComputations) max 0

      while (currResult.depth != target) {
        if (currResult.depth - currResult.numComputations >= target) {
          // in this case the target lies outside the current result
          currResult = currResult.parentOpt.get
          logInfo (s"Target(${target}) not in the current result." +
            s" Proceeding to ${currResult}.")

        } else {
          // in this case the target lies within this result, we then walk
          // through the computations and detach the sub-pipeline wanted
          val _target = currResult.numComputations - (
            currResult.depth - target) max 0
          val curr = getComputationContainer[E].take(_target)
          val newConfig = config.withNewComputation (curr)
          currResult = currResult.copy(config = newConfig)

          // this should always holds, breaking this loop right after
          assert (currResult.depth == target,
            s"depth=${currResult.depth} target=${target} _target=${_target}" +
            s" numComputations=${currResult.numComputations}")
        }
      }
      logInfo (s"Backward exploration from ${this} to ${currResult}")
    }

    currResult
  }

  /**
   * This function will handle to the user a new result with a new configuration
   * NOTE: The configuration will make changes to the current scope, which may
   * include several steps
   *
   * @param key id of the configuration
   * @param value value of the new configuration
   *
   * @return new result
   */
  def set(key: String, value: Any): Fractoid[E] = {

    def setRec(curr: Fractoid[E]): Fractoid[E] = {
      if (curr.scope == this.scope) {
        val parent = if (curr.parentOpt.isDefined) {
          setRec(curr.parentOpt.get)
        } else {
          null
        }
        curr.copy (config = curr.config.withNewConfig (key,value),
          parentOpt = Option(parent))
      } else {
        curr
      }
    }

    setRec(this)
  }

  /**
   * This function will handle to the user a new result without a configuration
   * NOTE: The configuration will make changes to the current scope, which may
   * include several steps
   *
   * @param key id of the configuration
   *
   * @return new result
   */
  def unset(key: String): Fractoid[E] = {

    def unsetRec(curr: Fractoid[E]): Fractoid[E] = {
      if (curr.scope == this.scope) {
        val parent = if (curr.parentOpt.isDefined) {
          unsetRec(curr.parentOpt.get)
        } else {
          null
        }
        curr.copy (config = curr.config.withoutConfig (key),
          parentOpt = Option(parent))
      } else {
        curr
      }
    }

    unsetRec(this)
  }

  /**
   * This function will handle to the user a new result with new configurations
   *
   * @param configMap new configurations as a map (configName,configValue)
   *
   * @return new result
   */
  def set(configMap: Map[String,Any]): Fractoid[E] = {
    this.copy (config = config.withNewConfig (configMap))
  }

  /**
   * Auxiliary function for handling computation containers that were not set in
   * this result
   */
  private def getComputationContainer [E <: Subgraph]
    : ComputationContainer[E] = {
    try {
      var container: Computation[E] = config.computationContainer[E]
      container.asInstanceOf[ComputationContainer[E]]
    } catch {
      case e: RuntimeException =>
        logWarning (s"No computation container was set." +
          s" Please start with 'edgeInducedComputation' or" +
          s" 'vertexInducedComputation' from ArabesqueGraph." +
          s" Error message: ${e.getMessage}")
        return null
    }
  }

  /**
   * Handle the creation of a next result.
   */
  private def handleNextResult(result: Fractoid[E],
                               newConfig: SparkConfiguration[E] = config)
    : Fractoid[E] = if (result.mustSync || isPersisted) {
    logInfo (s"Adding sync barrier between ${this} and ${result}")
    result.copy(scope = this.scope + 1,
      step = this.step + 1, parentOpt = Some(this),
      storageLevel = StorageLevel.NONE)
  } else {
    logInfo (s"Next result. Appending ${result} to ${this}")
    val nextContainer = result.getComputationContainer[E]
    withNextComputation (nextContainer, newConfig)
  }

  /**
   * Create an empty computation that inherits all this result's configurations
   * but the computation container itself
   */
  private def emptyComputation: Fractoid[E] = {
    val ec = config.confs.get(SparkConfiguration.COMPUTATION_CONTAINER) match {
      case Some(cc: ComputationContainer[_]) =>
        this.set(
          SparkConfiguration.COMPUTATION_CONTAINER,
          cc.asLastComputation.clear())
      case _ =>
        this
    }
    ec.unset(SparkConfiguration.MASTER_COMPUTATION_CONTAINER)
  }

  /****** Arabesque Scala API: High Level API ******/

  /**
   * Perform a single standard expansion step over the existing Subgraphs
   *
   * @return new result
   */
  def expand: Fractoid[E] = {
    val expandComp = emptyComputation.
      withExpandCompute(null).
      copy(mustSync = false)
    handleNextResult(expandComp)
  }

  /**
   * Perform *n* expansion steps at once
   *
   * @param n number of expansions
   *
   * @return new result
   */
  def expand(n: Int): Fractoid[E] = {
    var curr = this
    logInfo(s"ExpandBefore ${curr}")
    for (i <- 0 until n) curr = curr.expand
    logInfo(s"ExpandAfter ${curr}")
    curr
  }

  /**
   * Perform a expansion step in a particular way, defined by *expansionCompute*
   *
   * @param expandCompute function that receives an subgraph and returns zero
   * or more Subgraphs (iterator)
   *
   * @return new result
   */
  def expand(expandCompute: (E,Computation[E]) => Iterator[E])
    : Fractoid[E] = {
    val expandComp = emptyComputation.
      withExpandCompute(expandCompute)
    handleNextResult(expandComp)
  }

  /**
   * Perform a expansion step in a particular way, defined by
   * *getPossibleExtensions*
   *
   * @param getPossibleExtensions function that receives an subgraph and
   * returns zero or more Subgraphs (collection)
   *
   * @return new result
   */
  def extend(getPossibleExtensions: (E,Computation[E]) => IntCollection)
    : Fractoid[E] = {
    val newConfig = config.withNewComputation (
      getComputationContainer[E].
      withNewFunctions (getPossibleExtensionsOpt = Option(getPossibleExtensions)))
    this.copy (config = newConfig)
  }

  /**
   * Filter the existing Subgraphs based on a function
   *
   * @param filter function that decides whether an subgraph should be kept or
   * discarded
   *
   * @return new result
   */
  def filter(filter: (E,Computation[E]) => Boolean): Fractoid[E] = {
    ClosureCleaner.clean(filter)
    val filterComp = emptyComputation.
      withExpandCompute((e,c) => Iterator(e)).
      withFilter(filter)
    handleNextResult(filterComp)
  }

  /**
   * Filter the extensions of this computation
   *
   * @param efilter function that decides whether an extension is valid or not
   *
   * @return new result
   */
  def efilter(
      efilter: (E,Int,Computation[E]) => Boolean): Fractoid[E] = {
    val filter = new WordFilterFunc [E] {
      def apply(e: E, w: Int, c: Computation[E]): Boolean = efilter(e, w, c)
    }
    withWordFilter(filter)
  }

  /**
   * Filter the existing Subgraphs based on a aggregation
   *
   * @param filter function that decides whether an subgraph should be kept or
   * discarded
   *
   * @return new result
   */
  def filterByAgg [K <: Writable : ClassTag, V <: Writable : ClassTag] (
      agg: String)(
      filter: (E,AggregationStorage[K,V]) => Boolean): Fractoid[E] = {

    val filterFunc = (e: E, c: Computation[E]) => {
      filter(e, c.readAggregation(agg))
    }

    val filterComp = emptyComputation.
      withExpandCompute((e,c) => Iterator(e)).
      withFilter(filterFunc).
      copy(mustSync = true)

    handleNextResult(filterComp)
  }

  /**
   * Register an aggregation and include the aggregation map to the existing
   * Subgraphs.
   *
   * @param name custom name of this aggregation --> this is used later for
   * retrieving the aggregation results
   * @param aggregationKey function that extracts the key from the embeddding
   * @param aggregationValue function that extracts the value from the subgraph
   * @param reductionFunction function used to reduce the values
   *
   * @return new result
   */
  def aggregate [K <: Writable : ClassTag, V <: Writable : ClassTag] (
      name: String,
      aggregationKey: (E,Computation[E],K) => K,
      aggregationValue: (E,Computation[E],V) => V,
      reductionFunction: (V,V) => V,
      endAggregationFunction: EndAggregationFunction[K,V] = null,
      isIncremental: Boolean = false
    ): Fractoid[E] = {
    withAggregationRegistered(
      name,
      aggregationKey = aggregationKey,
      aggregationValue = aggregationValue,
      reductionFunction = new ReductionFunctionContainer(reductionFunction),
      endAggregationFunction = endAggregationFunction,
      isIncremental = isIncremental).
    withAggregation [K,V] (name, (_,_) => true)
  }

  /**
   * Register an aggregation and include the aggregation map to the existing
   * Subgraphs.
   *
   * @param name custom name of this aggregation --> this is used later for
   * retrieving the aggregation results
   * @param reductionFunction function used to reduce the values
   *
   * @return new result
   */
  def aggregateAll [K <: Writable : ClassTag, V <: Writable : ClassTag] (
      name: String,
      func: (E,Computation[E]) => Iterator[(K,V)],
      reductionFunction: (V,V) => V): Fractoid[E] = {
    withAggregationRegisteredIterator [K,V] (
      name,
      reductionFunction = new ReductionFunctionContainer(reductionFunction)).
    withAggregationIterator(name, func)
  }


  /****** Arabesque Scala API: ComputationContainer ******/

  /**
   * Updates the process function of the underlying computation container.
   *
   * @param process process function to be applied to each subgraph produced
   *
   * @return new result
   */
  def withProcess (process: (E,Computation[E]) => Unit): Fractoid[E] = {
    val newComp = getComputationContainer[E].withNewFunctions (
      processOpt = Option(process))
    val newConfig = config.withNewComputation (newComp)
    this.copy (config = newConfig)
  }

  /**
   * Append a body function to the process
   *
   * @param func function to be appended
   *
   * @return new result
   */
  def withProcessInc (func: (E,Computation[E]) => Unit): Fractoid[E] = {
    // get the current process function
    val oldProcess = getComputationContainer[E].processOpt match {
      case Some(process) => process
      case None => (e: E, c: Computation[E]) => {}
    }

    // incremental process
    val process = (e: E, c: Computation[E]) => {
      oldProcess (e, c)
      func (e, c)
    }

    withProcess(process)
  }

  /**
   * Updates the filter function of the underlying computation container.
   *
   * @param filter filter function that determines whether Subgraphs must be
   * further processed or not.
   *
   * @return new result
   */
  def withFilter (filter: (E,Computation[E]) => Boolean): Fractoid[E] = {
    val newConfig = config.withNewComputation (
      getComputationContainer[E].withNewFunctions (filterOpt = Option(filter)))
    this.copy (config = newConfig)
  }

  /**
   * Updates the word filter function of the underlying computation container.
   *
   * @param filter filter function that determines whether an extension must be
   * further processed or not.
   *
   * @return new result
   */
  def withWordFilter (
      filter: WordFilterFunc[E]): Fractoid[E] = {

    val newConfig = config.withNewComputation (
      getComputationContainer[E].
      withNewFunctions (wordFilterOpt = Option(filter)))
    this.copy (config = newConfig)
  }

  /**
   * Updates the shouldExpand function of the underlying computation container.
   *
   * @param shouldExpand function that determines whether the Subgraphs
   * produced must be extended or not.
   */
  def withShouldExpand (shouldExpand: (E,Computation[E]) => Boolean)
    : Fractoid[E] = {
    val newConfig = config.withNewComputation (
      getComputationContainer[E].withNewFunctions (
        shouldExpandOpt = Option(shouldExpand)))
    this.copy (config = newConfig)
  }

  /**
   * Updates the shouldOutput function of the underlying computation container.
   *
   * @param shouldOutput function that determines whether we should output the
   * subgraph or not
   */
  def withOutput (shouldOutput: (E,Computation[E]) => Boolean)
    : Fractoid[E] = {

    withProcessInc (
      (e: E, c: Computation[E]) => {
        if (shouldOutput(e,c)) {
          c.output(e)
        }
      }
    )
  }

  /**
   * Include an aggregation map into the process function
   *
   * @param name aggregation name
   * @param func function that returns aggregation pairs
   *
   * @return new result
   */
  def withAggregationIterator [K <: Writable, V <: Writable] (name: String,
      func: (E,Computation[E]) => Iterator[(K,V)]): Fractoid[E] = {

    withProcessInc (
      (e: E, c: Computation[E]) => {
        val iter = func(e, c)
        while (iter.hasNext()) {
          val kv = iter.next()
          c.map(name, kv._1, kv._2)
        }
      }
    )
  }

  /**
   * Include an aggregation map into the process function
   *
   * @param name aggregation name
   * @param shouldAggregate condition for aggregating an subgraph
   *
   * @return new result
   */
  def withAggregation [K <: Writable, V <: Writable] (name: String,
      shouldAggregate: (E,Computation[E]) => Boolean): Fractoid[E] = {

    if (!aggFuncs.get(name).isDefined) {
      logWarning (s"Unknown aggregation ${name}." +
        s" Please register it first with *withAggregationRegistered*")
      return this
    }

    val (_aggregationKey, _aggregationValue) = aggFuncs(name)
    val (aggregationKey, aggregationValue) = (
      _aggregationKey.asInstanceOf[(E,Computation[E],K) => K],
      _aggregationValue.asInstanceOf[(E,Computation[E],V) => V]
      )

    withProcessInc (
      (e: E, c: Computation[E]) => {
        // TODO: remove shouldAggregate properly
        //if (shouldAggregate(e,c)) {
          val aggStorage = c.getAggregationStorage[K,V](name)
          val k = aggregationKey(e, c, aggStorage.reusableKey())
          val v = aggregationValue(e, c, aggStorage.reusableValue())
          aggStorage.aggregateWithReusables(k, v)
          //c.map (name, aggregationKey(e,c), aggregationValue(e,c))
        //}
      }
    )
  }

  /**
   * Updates the aggregationFilter function of the underlying computation
   * container.
   *
   * @param filter function that filters Subgraphs in the aggregation phase.
   *
   * @return new result
   */
  def withAggregationFilter (filter: (E,Computation[E]) => Boolean)
    : Fractoid[E] = {
    val newConfig = config.withNewComputation (
      getComputationContainer[E].withNewFunctions (
        aggregationFilterOpt = Option(filter)))
    this.copy (config = newConfig)
  }

  /**
   * Updates the aggregationFilter function regarding patterns instead of
   * subgraph.
   *
   * @param filter function that filters Subgraphs of a given pattern in the
   * aggregation phase.
   *
   * @return new result
   */
  def withPatternAggregationFilter (
      filter: (Pattern,Computation[E]) => Boolean): Fractoid[E] = {
    val newConfig = config.withNewComputation (
      getComputationContainer[E].withNewFunctions (
        pAggregationFilterOpt = Option(filter)))
    this.copy (config = newConfig)
  }

  /**
   * Updates the aggregationProcess function of the underlying computation
   * container.
   *
   * @param process function to be applied to each subgraph in the aggregation
   * phase.
   *
   * @return new result
   */
  def withAggregationProcess (process: (E,Computation[E]) => Unit)
    : Fractoid[E] = {
    val newConfig = config.withNewComputation (
      getComputationContainer[E].withNewFunctions (
        aggregationProcessOpt = Option(process)))
    this.copy (config = newConfig)
  }

  /**
   * Updates the handleNoExpansions function of the underlying computation
   * container.
   *
   * @param func callback for Subgraphs that do not produce any expansion.
   *
   * @return new result
   */
  def withHandleNoExpansions (func: (E,Computation[E]) => Unit)
    : Fractoid[E] = {
    val newConfig = config.withNewComputation (
      getComputationContainer[E].withNewFunctions (
        handleNoExpansionsOpt = Option(func)))
    this.copy (config = newConfig)
  }

  /**
   * Updates the getPossibleExtensions function of the underlying computation
   * container.
   *
   * @param func that returns the possible extensions.
   *
   * @return new result
   */
  def withGetPossibleExtensions (func: (E,Computation[E]) => IntCollection)
    : Fractoid[E] = {
    val newConfig = config.withNewComputation (
      getComputationContainer[E].withNewFunctions (
        getPossibleExtensionsOpt = Option(func)))
    this.copy (config = newConfig)
  }

  /**
   * Updates the init function of the underlying computation
   * container.
   *
   * @param init initialization function for the computation
   *
   * @return new result
   */
  def withInit (init: (Computation[E]) => Unit): Fractoid[E] = {
    val newConfig = config.withNewComputation (
      getComputationContainer[E].withNewFunctions (initOpt = Option(init)))
    this.copy (config = newConfig)
  }

  /**
   * Updates the initAggregations function of the underlying computation
   * container.
   *
   * @param initAggregations function that initializes the aggregations for the
   * computation
   *
   * @return new result
   */
  def withInitAggregations (initAggregations: (Computation[E]) => Unit)
    : Fractoid[E] = {
    val newConfig = config.withNewComputation (
      getComputationContainer[E].withNewFunctions (
        initAggregationsOpt = Option(initAggregations)))
    this.copy (config = newConfig)
  }

  /**
   * Adds a new aggregation to the computation
   *
   * @param name identifier of this new aggregation
   * @param reductionFunction the function that aggregates two values
   * @param endAggregationFunction the function that is applied at the end of
   * each local aggregation, in the workers
   * @param persistent whether this aggregation must be persisted in each
   * superstep or not
   * @param aggStorageClass custom aggregation storage implementation
   *
   * @return new result
   */
  def withAggregationRegisteredIterator [
        K <: Writable : ClassTag, V <: Writable: ClassTag
      ] (
      name: String,
      reductionFunction: ReductionFunction[V],
      endAggregationFunction: EndAggregationFunction[K,V] = null,
      persistent: Boolean = false,
      aggStorageClass: Class[_ <: AggregationStorage[K,V]] =
        classOf[AggregationStorage[K,V]],
      isIncremental: Boolean = false)
    : Fractoid[E] = {

    // TODO: check whether this aggregation is already registered and act
    // properly

    // if the user specifies *Pattern* as the key, we must find the concrete
    // implementation within the Configuration before registering the
    // aggregation
    val _keyClass = implicitly[ClassTag[K]].runtimeClass
    val keyClass = if (_keyClass == classOf[Pattern]) {
      config.getPatternClass().asInstanceOf[Class[K]]
    } else {
      _keyClass.asInstanceOf[Class[K]]
    }
    val valueClass = implicitly[ClassTag[V]].runtimeClass.asInstanceOf[Class[V]]

    // get the old init aggregations function in order to compose it
    val oldInitAggregation = getComputationContainer[E].
    initAggregationsOpt match {
      case Some(initAggregations) => initAggregations
      case None => (c: Computation[E]) => {}
    }

    // construct an incremental init aggregations function
    val initAggregations = (c: Computation[E]) => {
      oldInitAggregation (c) // init aggregations so far
      c.getConfig().registerAggregation (name, aggStorageClass, keyClass,
        valueClass, persistent, reductionFunction, endAggregationFunction,
        isIncremental)
    }

    withInitAggregations (initAggregations)
  }

  /**
   * Adds a new aggregation to the computation
   *
   * @param name identifier of this new aggregation
   * @param reductionFunction the function that aggregates two values
   * @param endAggregationFunction the function that is applied at the end of
   * each local aggregation, in the workers
   * @param persistent whether this aggregation must be persisted in each
   * superstep or not
   * @param aggStorageClass custom aggregation storage implementation
   *
   * @return new result
   */
  def withAggregationRegistered [
        K <: Writable : ClassTag, V <: Writable: ClassTag
      ] (
      name: String,
      reductionFunction: ReductionFunction[V],
      endAggregationFunction: EndAggregationFunction[K,V] = null,
      persistent: Boolean = false,
      aggStorageClass: Class[_ <: AggregationStorage[K,V]] =
        classOf[AggregationStorage[K,V]],
      aggregationKey: (E, Computation[E], K) => K =
        (e: E, c: Computation[E], k: K) => null.asInstanceOf[K],
      aggregationValue: (E, Computation[E], V) => V =
        (e: E, c: Computation[E], v: V) => null.asInstanceOf[V],
      isIncremental: Boolean = false)
    : Fractoid[E] = {

    // TODO: check whether this aggregation is already registered and act
    // properly

    // if the user specifies *Pattern* as the key, we must find the concrete
    // implementation within the Configuration before registering the
    // aggregation
    val _keyClass = implicitly[ClassTag[K]].runtimeClass
    val keyClass = if (_keyClass == classOf[Pattern]) {
      config.getPatternClass().asInstanceOf[Class[K]]
    } else {
      _keyClass.asInstanceOf[Class[K]]
    }
    val valueClass = implicitly[ClassTag[V]].runtimeClass.asInstanceOf[Class[V]]

    // get the old init aggregations function in order to compose it
    val oldInitAggregation = getComputationContainer[E].
    initAggregationsOpt match {
      case Some(initAggregations) => initAggregations
      case None => (c: Computation[E]) => {}
    }

    // construct an incremental init aggregations function
    val initAggregations = (c: Computation[E]) => {
      oldInitAggregation (c) // init aggregations so far
      c.getConfig().registerAggregation (name, aggStorageClass, keyClass,
        valueClass, persistent, reductionFunction, endAggregationFunction,
        isIncremental)
    }

    withInitAggregations (initAggregations).copy (
      aggFuncs = aggFuncs ++ Map(name -> (aggregationKey, aggregationValue))
    )
  }

  /**
   * Adds a new aggregation to the computation
   *
   * @param name identifier of this new aggregation
   * @param reductionFunction the function that aggregates two values
   *
   * @return new result
   */
  def withAggregationRegistered [
      K <: Writable : ClassTag, V <: Writable : ClassTag
      ] (name: String)(
        aggregationKey: (E, Computation[E], K) => K,
        aggregationValue: (E, Computation[E], V) => V,
        reductionFunction: (V,V) => V
      ): Fractoid[E] = {
    withAggregationRegistered [K,V] (name,
      aggregationKey = aggregationKey,
      aggregationValue = aggregationValue,
      reductionFunction = new ReductionFunctionContainer [V] (reductionFunction)
      )
  }

  /**
   * Adds a new aggregation to the computation
   *
   * @param name identifier of this new aggregation
   * @param reductionFunction the function that aggregates two values
   * @param endAggregationFunction the function that is applied at the end of
   * each local aggregation, in the workers
   *
   * @return new result
   */
  def withAggregationRegistered [
      K <: Writable : ClassTag, V <: Writable : ClassTag
      ] (name: String, reductionFunction: (V,V) => V,
      endAggregationFunction: (AggregationStorage[K,V]) => Unit)
      : Fractoid[E] = {
    withAggregationRegistered [K,V] (name,
      new ReductionFunctionContainer [V] (reductionFunction),
      new EndAggregationFunctionContainer [K,V] (endAggregationFunction))
  }

  /**
   * Specify a custom expand function to the computation
   *
   * @param expandCompute expand function
   *
   * @return new result
   */
  def withExpandCompute (expandCompute: (E,Computation[E]) => Iterator[E])
    : Fractoid[E] = {
    val newConfig = if (expandCompute != null) {
      config.withNewComputation (
        getComputationContainer[E].withNewFunctions (
          expandComputeOpt = Option((e,c) => expandCompute(e,c).asJava)
        )
      )
    } else {
      config.withNewComputation (
        getComputationContainer[E].withNewFunctions (expandComputeOpt = None)
      )
    }
    this.copy (config = newConfig)
  }

  /**
   * Return a new result with the computation appended.
   */
  def withNextComputation (nextComputation: Computation[E],
      newConfig: SparkConfiguration[E] = config)
    : Fractoid[E] = {
    logInfo (s"Appending ${nextComputation} to ${getComputationContainer[E]}")
    val _newConfig = newConfig.withNewComputation (
      getComputationContainer[E].withComputationAppended (nextComputation)
    )
    this.copy (config = _newConfig, storageLevel = StorageLevel.NONE)
  }

  /****** Arabesque Scala API: MasterComputationContainer ******/

  /**
   * Updates the init function of the underlying master computation
   * container.
   *
   * @param init initialization function for the master computation
   *
   * @return new result
   */
  def withMasterInit (init: (MasterComputation) => Unit): Fractoid[E] = {
    val newConfig = config.withNewMasterComputation (
      config.masterComputationContainer.withNewFunctions (
        initOpt = Option(init))
      )
    this.copy (config = newConfig)
  }

  /**
   * Updates the compute function of the underlying master computation
   * container.
   *
   * @param compute callback executed at the end of each superstep in the master
   *
   * @return new result
   */
  def withMasterCompute (compute: (MasterComputation) => Unit)
    : Fractoid[E] = {
    val newConfig = config.withNewMasterComputation (
      config.masterComputationContainer.withNewFunctions (
        computeOpt = Option(compute)))
    this.copy (config = newConfig)
  }

  /****** Arabesque Scala API: Built-in algorithms ******/

  /**
   * Check whether the current subgraph parameter is compatible with another
   */
  private def extensibleFrom [EE: ClassTag]: Boolean = {
    classTag[EE].runtimeClass == classTag[E].runtimeClass
  }

  /**
   * Build a Motifs computation from the current computation
   */
  def motifs: Fractoid[VertexInducedSubgraph] = {
    if (!extensibleFrom [VertexInducedSubgraph]) {
      throw new RuntimeException (
        s"${this} should be induced by vertices to be extended to Motifs")
    } else {
      val motifsRes = arabGraph.motifs.copy(stepByStep = stepByStep)
      handleNextResult(
        motifsRes.asInstanceOf[Fractoid[E]],
        motifsRes.config.asInstanceOf[SparkConfiguration[E]]).
      asInstanceOf[Fractoid[VertexInducedSubgraph]]
    }
  }

  /**
   * Build a Cliques computation from the current computation
   */
  def cliques: Fractoid[VertexInducedSubgraph] = {
    if (!extensibleFrom [VertexInducedSubgraph]) {
      throw new RuntimeException (
        s"${this} should be induced by vertices to be extended to Cliques")
    } else {
      val cliquesRes = arabGraph.cliques.copy(stepByStep = stepByStep)
      handleNextResult(
        cliquesRes.asInstanceOf[Fractoid[E]],
        cliquesRes.config.asInstanceOf[SparkConfiguration[E]]).
      asInstanceOf[Fractoid[VertexInducedSubgraph]]
    }
  }

  /**
   * Build a Triangles computation from the current computation
   */
  def triangles: Fractoid[VertexInducedSubgraph] = {
    if (!extensibleFrom [VertexInducedSubgraph]) {
      throw new RuntimeException (
        s"${this} should be induced by vertices to be extended to Triangles")
    } else {
      val trianglesRes = arabGraph.triangles.copy(stepByStep = stepByStep)
      handleNextResult(
        trianglesRes.asInstanceOf[Fractoid[E]],
        trianglesRes.config.asInstanceOf[SparkConfiguration[E]]).
      asInstanceOf[Fractoid[VertexInducedSubgraph]]
    }
  }

  /**
   * Build a FSM computation from the current computation
   */
  def fsm(support: Int): Fractoid[EdgeInducedSubgraph] = {
    if (!extensibleFrom [EdgeInducedSubgraph]) {
      throw new RuntimeException (
        s"${this} should be induced by edges to be extended to FSM")
    } else {
      val fsmRes = arabGraph.fsm(support).copy(stepByStep = stepByStep)
      handleNextResult(
        fsmRes.asInstanceOf[Fractoid[E]],
        fsmRes.config.asInstanceOf[SparkConfiguration[E]]).
      asInstanceOf[Fractoid[EdgeInducedSubgraph]]
    }
  }

  override def toString: String = {
    def computationToString: String = config.computationContainerOpt match {
      case Some(cc) =>
        cc.toString
      case None =>
        s"${config.getString(Configuration.CONF_COMPUTATION_CLASS,"")}"
    }

    s"Arabesque(scope=${scope}, step=${step}, depth=${depth}," + 
    s" stepByStep=${stepByStep}," +
    s" computation=${computationToString}," +
    s" mustSync=${mustSync}, storageLevel=${storageLevel}," +
    s" outputPath=${config.getOutputPath}," +
    s" isOutputActive=${config.isOutputActive})"
  }

  def toDebugString: String = parentOpt match {
    case Some(parent) =>
      s"${parent.toDebugString}\n${toString}"
    case None =>
      toString
  }

}