package roar.hbase.services

import java.io.File

import org.apache.hadoop.fs.Path
import org.apache.hadoop.hbase.CellUtil
import org.apache.hadoop.hbase.client.{Delete, Get}
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment
import org.apache.hadoop.hbase.regionserver.HRegion
import org.apache.hadoop.hbase.regionserver.wal.WALEdit
import org.apache.hadoop.hbase.util.FSUtils
import org.apache.hadoop.io.IOUtils
import org.apache.lucene.index._
import org.apache.lucene.store.{Directory, FSDirectory}
import org.apache.solr.core.DirectoryFactory
import org.apache.solr.core.DirectoryFactory.DirContext
import roar.api.meta.ResourceDefinition
import roar.hbase.RoarHbaseConstants
import roar.hbase.services.DocumentSource.ObjectIdSeqFinder
import stark.utils.services.LoggerSupport

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration.Duration


/**
  * support region index
   create 'trace',  {NAME => 'info', COMPRESSION => 'SNAPPY'},{NAME=>'_seq'},
   {NUMREGIONS => 10 * 5, SPLITALGO => 'HexStringSplit',MAX_FILESIZE=>'1000000000000000'}
  *
  * alter 'trace', {METHOD => 'table_att', SPLIT_POLICY => 'org.apache.hadoop.hbase.regionserver.DisabledRegionSplitPolicy', MAX_FILESIZE => '100000000000'}
  *
  * @author <a href="mailto:jcai@ganshane.com">Jun Tsai</a>
  * @since 2016-07-06
  */
trait RegionIndexSupport {
  this:RegionCoprocessorEnvironmentSupport with LoggerSupport =>
  protected var indexWriterOpt:Option[IndexWriter] = None
  protected var rd:ResourceDefinition = _
  private var flushIndexFuture:Future[Unit] = _
  private var hdfsDir:Option[Directory] = None

  private[hbase] def openIndexWriter():Unit= {
    val tableName = coprocessorEnv.getRegion.getTableDesc.getTableName
    val regionEncodedName = coprocessorEnv.getRegionInfo.getEncodedName
    val resourceDefineOpt = RegionServerData.regionServerResources.get(tableName.getNameAsString)
    resourceDefineOpt match {
      case Some(rd) =>
        this.rd = rd
        val regionIndexPath = RoarHbaseConstants.REGION_INDEX_PATH_FORMAT.format(regionEncodedName)

        val rootDir = FSUtils.getRootDir(coprocessorEnv.getConfiguration)
        val tableDir = FSUtils.getTableDir(rootDir, tableName)

        val indexPath = new Path(tableDir, regionIndexPath)
        logger.info("create index with path {}", indexPath)

        val directory =
        if(indexPath.toString.startsWith("file"))
          FSDirectory.open(new File(indexPath.toUri).toPath)
        else {
          val dir = RegionServerData.directoryFactory.get(indexPath.toString, DirContext.DEFAULT, DirectoryFactory.LOCK_TYPE_HDFS)
          hdfsDir = Some(dir)
          dir
        }
         //new HdfsDirectory(indexPath, HdfsLockFactoryInHbase, coprocessorEnv.getConfiguration)

        // disable compound file to improve performance
        // also see http://lucene.472066.n3.nabble.com/Questions-on-compound-file-format-td489105.html

        val config = new IndexWriterConfig(RoarHbaseConstants.defaultAnalyzer)
        config.setUseCompoundFile(false)
        config.setRAMBufferSizeMB(128)
//        config.setMaxBufferedDocs(1000)

        val mergePolicy = new TieredMergePolicy//new LogByteSizeMergePolicy()
        mergePolicy.setNoCFSRatio(0)
//      val mergePolicy = new LogByteSizeMergePolicy()
//        mergePolicy.setMinMergeMB(10 * 1024 * 1024)
        mergePolicy.setMaxMergeAtOnce(5)
        mergePolicy.setSegmentsPerTier(5)
        // compound files cannot be used with HDFS
        //    mergePolicy.setUseCompoundFile(false)
        config.setMergePolicy(mergePolicy)
//        config.setMergeScheduler(new SerialMergeScheduler())
        indexWriterOpt = Some(new IndexWriter(directory, config))
      case None=>
        info("{} index not supported",tableName.getNameAsString)
    }
  }
  private[hbase] def index(timestamp:Long,row:Array[Byte],finder:ObjectIdSeqFinder=IndexHelper.findObjectIdSeq(region)): Unit = {
    indexWriterOpt foreach {indexWriter=>
      /**
        * 因为两次put针对不同的column,在put中并未包含全部的信息,
        * 如果仅仅使用put来进行索引,可能会导致丢失数据.
        * 这里使用一个Get从本Region区域找到全部数据进行索引
        */
      val get = new Get(row)
      /*
      if(put.getTimeStamp != HConstants.LATEST_TIMESTAMP){
        get.setTimeStamp(put.getTimeStamp)
      }
      */
      val result = coprocessorEnv.getRegion.get(get)
      val rowTerm = IndexHelper.createSIdTerm(result.getRow)
      debug("[{}] index row term {}",rd.name,rowTerm)
      val docOpt = RegionServerData.documentSource.newDocument(rd,timestamp,result,finder)
      docOpt.foreach(indexWriter.updateDocument(rowTerm, _))
    }
  }


  protected def indexWalEdit(logEdit: WALEdit) {
    val cells = logEdit.getCells
    val it = cells.iterator()
    while (it.hasNext) {
      val cell = it.next()
      index(cell.getTimestamp, CellUtil.cloneRow(cell))
    }
  }
  def prepareFlushIndex(): Unit ={
    info("prepare flush index")
    this.flushIndexFuture = Future {
      flushIndex()
      info("finish flush index")
    }
  }
  def waitForFlushIndexThreadFinished(): Unit ={
    Await.result(this.flushIndexFuture,Duration.Inf)
  }
  def deleteIndex(delete:Delete):Unit={
    indexWriterOpt foreach {indexWriter=>
      val rowTerm = IndexHelper.createSIdTerm(delete.getRow)
      debug("[{}] delete row term {}",rd.name,rowTerm)
      val get = new Get(delete.getRow)
      /*
      if(put.getTimeStamp != HConstants.LATEST_TIMESTAMP){
        get.setTimeStamp(put.getTimeStamp)
      }
      */
      val result = coprocessorEnv.getRegion.get(get)

      val docOpt = RegionServerData.documentSource.newDocument(rd,delete.getTimeStamp,result,IndexHelper.findObjectIdSeq(region))
      docOpt match{
        case Some(doc) =>
          indexWriter.updateDocument(rowTerm,doc)
        case None =>
          //delete current document
          indexWriter.deleteDocuments(rowTerm)
      }
    }
  }


  protected def flushIndex(): Unit ={
    //commit index to disk or dfs
    indexWriterOpt.foreach(_.commit())
  }
  /*
  protected def prepareSplitIndexAfterPONR(): Unit ={
    indexWriterOpt foreach { indexWriter =>
      //fetch daughters info from meta table
      val rss = coprocessorEnv.getRegionServerServices
      val conn = rss.getConnection
      val result = MetaTableAccessor.getRegionResult(conn,coprocessorEnv.getRegionInfo.getRegionName)
      val daughters = MetaTableAccessor.getDaughterRegions(result)
      val conf = coprocessorEnv.getConfiguration


      //create index transaction node
      val zkw = coprocessorEnv.getRegionServerServices.getZooKeeper
      val transactionPath = IndexSplitter.getTransactionPath(conf)

      //use daughter A to denote the transaction
      val daughterAPath = ZKUtil.joinZNode(transactionPath,daughters.getFirst.getEncodedName)
      //set parent and daughters data to daughter A path
      val builder = AdminProtos.GetOnlineRegionResponse.newBuilder()
      builder.addRegionInfo(HRegionInfo.convert(coprocessorEnv.getRegionInfo))
      builder.addRegionInfo(HRegionInfo.convert(daughters.getFirst))
      builder.addRegionInfo(HRegionInfo.convert(daughters.getSecond))
      ZKUtil.createSetData(zkw,daughterAPath,builder.build().toByteArray)

      val future = IndexSplitter.submitSplit(zkw,daughters.getFirst.getEncodedName,coprocessorEnv.getConfiguration)

      Await.result(future,Duration.Inf)
    }
  }
  protected def prepareSplitIndex(splitRow:Array[Byte]): Unit ={
  }
  protected def rollbackSplitIndex(): Unit ={
    //TODO How to stop scala future (index thread).
    splitterOpt.foreach{f=>
    }
  }

  /**
    * wait split index thread finish
    */
  protected def awaitSplitIndexComplete(l:Region,r:Region): Unit ={

    splitterOpt.foreach{f=>
      Await.result(f,Duration.Inf)
      info("finish to split index")
    }
  }
  */

  protected def maybeStopSplit: Boolean ={
    indexWriterOpt.isDefined
  }

  private[hbase] def closeIndex():Unit={
    indexWriterOpt.foreach{indexWriter=>
      logger.info("[{}] closing index writer...",rd.name)
      IOUtils.closeStream(indexWriter)
    }
  }
  protected def closeDirectory():Unit={
    hdfsDir.foreach{dir=>
      RegionServerData.directoryFactory.doneWithDirectory(dir)
      RegionServerData.directoryFactory.release(dir)
      IOUtils.closeStream(dir)
    }
  }
}

/**
  * provide RegionCoprocessor Environment
  */
trait RegionCoprocessorEnvironmentSupport{
  @inline
  def coprocessorEnv:RegionCoprocessorEnvironment
  def region:HRegion = coprocessorEnv.getRegion
}
