// Copyright 2013,2015,2016 the original author or authors. All rights reserved.
// site: http://www.ganshane.com
package roar.hbase.services

import org.apache.hadoop.hbase.client.Put
import org.apache.lucene.document.Document
import roar.hbase.model.ResourceDefinition

/**
 * 文档创建
  *
  * @author jcai
 */
trait DocumentCreator{
  def newDocument(rd:ResourceDefinition,put:Put): Document
}
trait DocumentSource{
  def newDocument(rd:ResourceDefinition,put:Put): Option[Document]
}
