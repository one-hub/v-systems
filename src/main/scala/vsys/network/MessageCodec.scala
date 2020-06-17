package vsys.network

import java.util

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec
import vsys.network.message._
import vsys.utils.ScorexLogging

import scala.util.{Failure, Success}

@Sharable
class MessageCodec(peerDatabase: PeerDatabase) extends MessageToMessageCodec[RawBytes, Message] with ScorexLogging {

  private val specs: Map[Byte, MessageSpec[_ <: AnyRef]] = BasicMessagesRepo.specs.map(s => s.messageCode -> s).toMap

  override def encode(ctx: ChannelHandlerContext, msg: Message, out: util.List[AnyRef]): Unit = msg match {
    case LocalScoreChanged(score) => out.add(RawBytes(ScoreMessageSpec.messageCode, ScoreMessageSpec.serializeData(score)))
    case GetPeers => out.add(RawBytes(GetPeersSpec.messageCode, Array[Byte]()))
    case k: KnownPeers => out.add(RawBytes(PeersSpec.messageCode, PeersSpec.serializeData(k)))
    case gs: GetSignatures => out.add(RawBytes(GetSignaturesSpec.messageCode, GetSignaturesSpec.serializeData(gs)))
    case s: Signatures => out.add(RawBytes(SignaturesSpec.messageCode, SignaturesSpec.serializeData(s)))
    case g: GetBlock => out.add(RawBytes(GetBlockSpec.messageCode, GetBlockSpec.serializeData(g)))
    case BlockForged(b) => out.add(RawBytes(BlockMessageSpec.messageCode, b.bytes))
    case r: RawBytes => out.add(r)
  }

  override def decode(ctx: ChannelHandlerContext, msg: RawBytes, out: util.List[AnyRef]): Unit = {
    specs(msg.code).deserializeData(msg.data) match {
      case Success(x) => out.add(x)
      case Failure(e) => block(ctx, e)
    }
  }

  protected def block(ctx: ChannelHandlerContext, e: Throwable): Unit = {
    peerDatabase.blacklistAndClose(ctx.channel(), s"Invalid message. ${e.getMessage}")
  }

}
