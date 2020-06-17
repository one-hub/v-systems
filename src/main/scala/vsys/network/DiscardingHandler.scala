package vsys.network

import java.util.concurrent.atomic.AtomicBoolean

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext}
import vsys.utils.ScorexLogging

@Sharable
class DiscardingHandler(blockchainReadiness : AtomicBoolean) extends ChannelDuplexHandler with ScorexLogging {
  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case RawBytes(code, _) if code == TransactionMessageSpec.messageCode && blockchainReadiness.get =>
      log.trace(s"${id(ctx)} Discarding incoming message $code")
    case _ => super.channelRead(ctx, msg)
  }
}
