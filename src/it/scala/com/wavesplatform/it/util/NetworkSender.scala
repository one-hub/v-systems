package com.wavesplatform.it.util

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

import com.wavesplatform.it.network.client.NetworkClient
import vsys.network.{PeerInfo, RawBytes}
import io.netty.channel.Channel
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.HashedWheelTimer
import io.netty.util.concurrent.GlobalEventExecutor

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class NetworkSender(address: InetSocketAddress, chainId: Char, name: String, nonce: Long) {
  private val retryTimer = new HashedWheelTimer()
  val allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
  val establishedConnections = new ConcurrentHashMap[Channel, PeerInfo]
  val c = new NetworkClient(chainId, name, nonce, allChannels, establishedConnections)
  c.connect(address)

  def sendByNetwork(messages: RawBytes*): Future[Unit] = {
    retryTimer.retryUntil(Future.successful(establishedConnections.size()), (size: Int) => size == 1, 1.seconds)
      .map(_ => {
      val channel = establishedConnections.asScala.head._1
      messages.foreach(msg => {
        channel.writeAndFlush(msg)
      })
    })
  }

  def close(): Unit = {
    retryTimer.stop()
    c.shutdown()
  }
}
