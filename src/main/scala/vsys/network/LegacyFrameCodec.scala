package vsys.network

import java.util

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled._
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import vsys.utils.crypto.hash.FastCryptographicHash
import vsys.network.message.Message._
import vsys.network.message.MessageSpec
import vsys.utils.ScorexLogging

import scala.util.control.NonFatal

class LegacyFrameCodec(peerDatabase: PeerDatabase) extends ByteToMessageCodec[RawBytes] with ScorexLogging {
  import LegacyFrameCodec._

  override def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[AnyRef]): Unit = try {
    require(in.readInt() == Magic, "invalid magic number")

    val code = in.readByte()
    require(messageSpecs.contains(code), s"Unexpected message code $code")

    val spec = messageSpecs(code)
    val length = in.readInt()
    require(length <= spec.maxLength, s"${spec.messageName} length $length exceeds ${spec.maxLength}")

    val dataBytes = new Array[Byte](length)
    if (length > 0) {
      val declaredChecksum = in.readSlice(ChecksumLength)
      in.readBytes(dataBytes)
      val actualChecksum = wrappedBuffer(FastCryptographicHash.hash(dataBytes), 0, ChecksumLength)

      require(declaredChecksum.equals(actualChecksum), "invalid checksum")
      actualChecksum.release()

    }

    out.add(RawBytes(code, dataBytes))
  } catch {
    case NonFatal(e) =>
      log.warn(s"${id(ctx)} Malformed network message", e)
      peerDatabase.blacklistAndClose(ctx.channel(), "Malformed network message")
  }

  override def encode(ctx: ChannelHandlerContext, msg: RawBytes, out: ByteBuf): Unit = {
    out.writeInt(Magic)
    out.writeByte(msg.code)
    if (msg.data.length > 0) {
      out.writeInt(msg.data.length)
      out.writeBytes(FastCryptographicHash.hash(msg.data), 0, ChecksumLength)
      out.writeBytes(msg.data)
    } else {
      out.writeInt(0)
    }
  }
}

object LegacyFrameCodec {
  val Magic = 0xbfadafbe

  private val messageSpecs: Map[Byte, MessageSpec[_ <: AnyRef]] =
    BasicMessagesRepo.specs.map(s => s.messageCode -> s).toMap
}
