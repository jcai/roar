package roar.hbase.services

import java.util

import org.apache.hadoop.hbase.CoprocessorEnvironment
import org.apache.hadoop.hbase.coprocessor.{BaseRegionServerObserver, RegionServerCoprocessorEnvironment}
import org.apache.hadoop.hbase.zookeeper.{ZKUtil, ZooKeeperListener, ZooKeeperWatcher}
import roar.hbase.RoarHbaseConstants
import roar.hbase.model.ResourceDefinition
import roar.hbase.services.RegionServerData.ResourceListener
import stark.utils.StarkUtilsConstants
import stark.utils.services.{LoggerSupport, XmlLoader}

import scala.collection.mutable.ArrayBuffer

/**
  * Region Server的Coprocessor
  *
  * @author <a href="mailto:jcai@ganshane.com">Jun Tsai</a>
  * @since 2016-07-06
  */
private[services] object RegionServerData extends LoggerSupport{

  var regionServerResources = Map[String, ResourceDefinition]()
  def addResources(zkw:ZooKeeperWatcher,resources:util.List[String]): Unit ={
    if(resources != null) {
      val it = resources.iterator()
      val buffer = new ArrayBuffer[ResourceDefinition](resources.size())
      while (it.hasNext) {
        val res = it.next()
        val resPath = ZKUtil.joinZNode(RoarHbaseConstants.RESOURCES_PATH, res)
        val data = ZKUtil.getDataAndWatch(zkw, resPath)
        val rd = XmlLoader.parseXML[ResourceDefinition](new String(data, StarkUtilsConstants.UTF8_ENCODING))
        buffer += rd
      }

      regionServerResources = buffer.map(x => (x.name, x)).toMap
    }
  }
  class ResourceListener(zkw:ZooKeeperWatcher) extends ZooKeeperListener(zkw) {
    override def nodeChildrenChanged(path: String): Unit = {
      debug("node:{} children changed",path)
      if(path == RoarHbaseConstants.RESOURCES_PATH){
        val resources = ZKUtil.listChildrenAndWatchThem(zkw,RoarHbaseConstants.RESOURCES_PATH)
        addResources(zkw,resources)
      }
    }

    override def nodeCreated(path: String): Unit = {
      super.nodeCreated(path)
    }

    override def nodeDeleted(path: String): Unit = {
      super.nodeDeleted(path)
    }

    override def nodeDataChanged(path: String): Unit = {
      super.nodeDataChanged(path)
    }
  }
}
class IndexRegionServerObserver extends BaseRegionServerObserver with LoggerSupport{
  override def start(env: CoprocessorEnvironment): Unit = {
    debug("start region server coprocess")
    val rss = env.asInstanceOf[RegionServerCoprocessorEnvironment].getRegionServerServices
    val zkw = rss.getZooKeeper
    zkw.registerListener(new ResourceListener(zkw))
    debug("watching {}",RoarHbaseConstants.RESOURCES_PATH)
    while(ZKUtil.checkExists(zkw,RoarHbaseConstants.RESOURCES_PATH) == -1){
      ZKUtil.createWithParents(zkw,RoarHbaseConstants.RESOURCES_PATH)
    }

    val resources = ZKUtil.listChildrenAndWatchThem(zkw,RoarHbaseConstants.RESOURCES_PATH)
    RegionServerData.addResources(zkw,resources)
    debug("finish start region server coprocessor,resource size:{}",RegionServerData.regionServerResources.size)
  }
}
