/**
 * Copyright 2014 the Akka Tracing contributors. See AUTHORS for more details.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.levkhomich.akka.tracing

import java.net.InetAddress
import java.nio.ByteBuffer
import javax.xml.bind.DatatypeConverter
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

import akka.actor.{Actor, Cancellable}
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TMemoryBuffer

private[tracing] object SpanHolderInternalAction {
  final case class Sample(ts: BaseTracingSupport, serviceName: String, rpcName: String, timestamp: Long)
  final case class Enqueue(msgId: Long, cancelJob: Boolean)
  case object SendEnqueued
  final case class AddAnnotation(msgId: Long, timestamp: Long, msg: String)
  final case class AddBinaryAnnotation(msgId: Long, key: String, value: ByteBuffer, valueType: thrift.AnnotationType)
  final case class CreateChildSpan(msgId: Long, parentId: Long)
  final case class SetSampleRate(sampleRate: Int)
}

/**
 * Internal API
 */
private[tracing] class SpanHolder(client: thrift.Scribe[Option], var sampleRate: Int) extends Actor {

  import SpanHolderInternalAction._

  private[this] var counter = 0L

  private[this] val spans = mutable.Map[Long, thrift.Span]()
  private[this] val sendJobs = mutable.Map[Long, Cancellable]()
  private[this] val endpoints = mutable.Map[Long, thrift.Endpoint]()
  private[this] val sendQueue = mutable.UnrolledBuffer[thrift.Span]()

  private[this] val protocolFactory = new TBinaryProtocol.Factory()

  private[this] val localAddress = ByteBuffer.wrap(InetAddress.getLocalHost.getAddress).getInt
  private[this] val unknownEndpoint = Some(thrift.Endpoint(localAddress, 0, "unknown"))

  private[this] val microTimeAdjustment = System.currentTimeMillis * 1000 - System.nanoTime / 1000

  context.system.scheduler.schedule(0.seconds, 2.seconds, self, SendEnqueued)

  override def receive: Receive = {
    case Sample(ts, serviceName, rpcName, timestamp) =>
      counter += 1
      lookup(ts.msgId) match {
        case None if counter % sampleRate == 0 =>
          val endpoint = thrift.Endpoint(localAddress, 0, serviceName)
          val serverRecvAnn = thrift.Annotation(adjustedMicroTime(timestamp), thrift.Constants.SERVER_RECV, Some(endpoint), None)
          if (ts.traceId.isEmpty)
            ts.setTraceId(Some(Random.nextLong()))
          createSpan(ts.msgId, ts.parentId, ts.traceId.get, rpcName, Seq(serverRecvAnn))
          endpoints.put(ts.msgId, endpoint)

        // TODO: check if it really needed
        case Some(spanInt) if spanInt.name != rpcName || !endpoints.contains(ts.msgId) =>
          spans.put(ts.msgId, spanInt.copy(name = rpcName))
          endpoints.put(ts.msgId, thrift.Endpoint(localAddress, 0, serviceName))

        case _ =>
      }

    case Enqueue(msgId, cancelJob) =>
      enqueue(msgId, cancelJob)

    case SendEnqueued =>
      send()

    case AddAnnotation(msgId, timestamp, msg) =>
      lookup(msgId) foreach { spanInt =>
        val a = thrift.Annotation(adjustedMicroTime(timestamp), msg, endpointFor(msgId), None)
        spans.put(msgId, spanInt.copy(annotations = a +: spanInt.annotations))
        if (a.value == thrift.Constants.SERVER_SEND) {
          enqueue(msgId, cancelJob = true)
        }
      }

    case AddBinaryAnnotation(msgId, key, value, valueType) =>
      lookup(msgId) foreach { spanInt =>
        val a = thrift.BinaryAnnotation(key, value, valueType, endpointFor(msgId))
        spans.put(msgId, spanInt.copy(binaryAnnotations = a +: spanInt.binaryAnnotations))
      }

    case CreateChildSpan(msgId, parentId) =>
      lookup(msgId) match {
        case Some(parentSpan) =>
          createSpan(msgId, Some(parentSpan.id), parentSpan.traceId)
        case _ =>
          None
      }

    case SetSampleRate(sampleRate) =>
      this.sampleRate = sampleRate
  }

  override def postStop(): Unit = {
    spans.keys.foreach(id =>
      enqueue(id, cancelJob = true)
    )
    send()
    super.postStop()
  }

  private def adjustedMicroTime(nanoTime: Long): Long =
    microTimeAdjustment + nanoTime / 1000

  @inline
  private def lookup(id: Long): Option[thrift.Span] =
    spans.get(id)

  private def createSpan(id: Long, parentId: Option[Long], traceId: Long, name: String = null,
                         annotations: Seq[thrift.Annotation] = Nil,
                         binaryAnnotations: Seq[thrift.BinaryAnnotation] = Nil): Unit = {
    sendJobs.put(id, context.system.scheduler.scheduleOnce(30.seconds, self, Enqueue(id, cancelJob = false)))
    spans.put(id, thrift.Span(traceId, name, id, parentId, annotations, binaryAnnotations))
  }

  private def enqueue(id: Long, cancelJob: Boolean): Unit = {
    sendJobs.remove(id).foreach(job => if (cancelJob) job.cancel())
    spans.remove(id).foreach(span => sendQueue.append(span))
  }

  private def send(): thrift.ResultCode = {
    if (!sendQueue.isEmpty) {
      val messages = sendQueue.map(spanToLogEntry)
      sendQueue.clear()
      client.log(messages).getOrElse(thrift.ResultCode.TryLater)
    } else
      thrift.ResultCode.Ok
  }

  private def spanToLogEntry(spanInt: thrift.Span): thrift.LogEntry = {
    val buffer = new TMemoryBuffer(1024)
    spanInt.write(protocolFactory.getProtocol(buffer))
    val thriftBytes = buffer.getArray.take(buffer.length)
    val encodedSpan = DatatypeConverter.printBase64Binary(thriftBytes) + '\n'
    thrift.LogEntry("zipkin", encodedSpan)
  }

  private def endpointFor(msgId: Long): Option[thrift.Endpoint] =
    endpoints.get(msgId).orElse(unknownEndpoint)

}
