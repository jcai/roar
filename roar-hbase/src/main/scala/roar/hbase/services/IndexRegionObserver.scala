package roar.hbase.services

import org.apache.hadoop.hbase.client.{Durability, Put}
import org.apache.hadoop.hbase.coprocessor.{BaseRegionObserver, ObserverContext, RegionCoprocessorEnvironment}
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest
import org.apache.hadoop.hbase.regionserver.wal.WALEdit
import org.apache.hadoop.hbase.regionserver.{Region, Store, StoreFile}
import stark.utils.services.LoggerSupport

/**
  * index region observer
  *
  * @author <a href="mailto:jcai@ganshane.com">Jun Tsai</a>
  * @since 2016-07-03
  */
class IndexRegionObserver extends BaseRegionObserver
  with RegionIndexSupport
  with RegionSearchSupport
  with RegionCoprocessorEnvironmentSupport
  with IndexSearchServiceSupport
  with LoggerSupport{

  private var _env:RegionCoprocessorEnvironment = _

  override def coprocessorEnv: RegionCoprocessorEnvironment = _env

  override def postOpen(e: ObserverContext[RegionCoprocessorEnvironment]): Unit = {
    _env = e.getEnvironment
    openIndexWriter()
    openSearcherManager()
  }

  override def postFlush(e: ObserverContext[RegionCoprocessorEnvironment]): Unit = {
    flushIndex()
  }

  override def postCompact(e: ObserverContext[RegionCoprocessorEnvironment], store: Store, resultFile: StoreFile, request: CompactionRequest): Unit = {
  }

  override def postClose(e: ObserverContext[RegionCoprocessorEnvironment], abortRequested: Boolean): Unit = {
    closeSearcher()
    closeIndex()
  }
  override def postPut(e: ObserverContext[RegionCoprocessorEnvironment], put: Put, edit: WALEdit, durability: Durability): Unit = {
    index(put)
    mybeRefresh()
  }


  override def postSplit(e: ObserverContext[RegionCoprocessorEnvironment], l: Region, r: Region): Unit = {
  }
}