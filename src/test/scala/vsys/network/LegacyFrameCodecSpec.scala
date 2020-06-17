package vsys.network

import vsys.blockchain.transaction.TransactionGen
import vsys.network.client.NopPeerDatabase
import io.netty.buffer.Unpooled
import io.netty.buffer.Unpooled.wrappedBuffer
import io.netty.channel.embedded.EmbeddedChannel
import org.scalamock.scalatest.MockFactory
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{FreeSpec, Matchers}
import vsys.utils.crypto.hash.FastCryptographicHash
import vsys.network.message.{Message => ScorexMessage}

class LegacyFrameCodecSpec extends FreeSpec
  with Matchers
  with MockFactory
  with PropertyChecks
  with GeneratorDrivenPropertyChecks
  with TransactionGen {

  "should handle a message with the maximal size" in forAll(randomProvenTransactionGen) { origTx =>
    val codec = new LegacyFrameCodec(NopPeerDatabase)

    val buff = Unpooled.buffer
    val txBytes = origTx.bytes
    val checkSum = wrappedBuffer(FastCryptographicHash.hash(txBytes), 0, ScorexMessage.ChecksumLength)

    buff.writeInt(LegacyFrameCodec.Magic)
    buff.writeByte(TransactionMessageSpec.messageCode)
    buff.writeInt(txBytes.length)
    buff.writeBytes(checkSum)
    buff.writeBytes(txBytes)

    val ch = new EmbeddedChannel(codec)
    ch.writeInbound(buff)

    val decodedBytes = ch.readInbound[RawBytes]()

    decodedBytes.code shouldBe TransactionMessageSpec.messageCode
    decodedBytes.data shouldEqual origTx.bytes
  }
}
