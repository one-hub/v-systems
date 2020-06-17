package com.wavesplatform.it.network.client

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

import vsys.Version
import vsys.network.{Handshake, HandshakeHandler, PeerInfo}
import vsys.settings._
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.group.ChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import vsys.utils.ScorexLogging

class NetworkClient(
    chainId: Char,
    nodeName: String,
    nonce: Long,
    allChannels: ChannelGroup,
    peerInfo: ConcurrentHashMap[Channel, PeerInfo]) extends ScorexLogging {

  private val bossGroup = new NioEventLoopGroup()
  private val workerGroup = new NioEventLoopGroup()

  private val handshake =
    Handshake(Constants.ApplicationName + chainId, Version.VersionTuple, nodeName,
      nonce, None)

  private val channels = new ConcurrentHashMap[InetSocketAddress, Channel]

  private val clientHandshakeHandler = new HandshakeHandler.Client(handshake, peerInfo, NopPeerDatabase)

  private val bootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioSocketChannel])
    .handler(new LegacyChannelInitializer(clientHandshakeHandler))

  def connect(remoteAddress: InetSocketAddress): Unit =
    channels.computeIfAbsent(remoteAddress, _ => {
      val chanF = bootstrap.connect(remoteAddress)
      val chan = chanF.channel()
      allChannels.add(chan)
      log.debug(s"${id(chan)} Connecting to $remoteAddress")
      chan.closeFuture().addListener { (chf: ChannelFuture) =>
        log.debug(s"${id(chf.channel)} Connection to $remoteAddress closed")
        allChannels.remove(chf.channel())
        channels.remove(remoteAddress, chf.channel())
      }
      chan
    })

  def shutdown(): Unit = try {
    allChannels.close().await()
    log.debug("Closed all channels")
  } finally {
    workerGroup.shutdownGracefully()
    bossGroup.shutdownGracefully()
  }
}
