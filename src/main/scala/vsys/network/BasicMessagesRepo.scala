package vsys.network

import java.net.{InetAddress, InetSocketAddress}
import java.util

import com.google.common.primitives.{Bytes, Ints}
import scorex.crypto.signatures.SigningFunctions.Signature
import vsys.network.message.Message._
import vsys.network.message._
import vsys.blockchain.block.Block
import vsys.blockchain.history.History
import vsys.blockchain.state.ByteStr
import vsys.blockchain.transaction.TransactionParser._
import vsys.blockchain.transaction.{Transaction, TransactionParser}

import scala.util.Try


object GetPeersSpec extends MessageSpec[GetPeers.type] {
  override val messageCode: Message.MessageCode = 1: Byte

  override val messageName: String = "GetPeers message"

  override def maxLength = 0

  override def deserializeData(bytes: Array[Byte]): Try[GetPeers.type] =
    Try {
      require(bytes.isEmpty, "Non-empty data for GetPeers")
      GetPeers
    }

  override def serializeData(data: GetPeers.type): Array[Byte] = Array()
}

object PeersSpec extends MessageSpec[KnownPeers] {
  private val AddressLength = 4
  private val PortLength = 4
  private val DataLength = 4

  override val messageCode: Message.MessageCode = 2: Byte

  override val messageName: String = "Peers message"


  override def maxLength = DataLength + 1000 * (AddressLength + PortLength)

  override def deserializeData(bytes: Array[Byte]): Try[KnownPeers] = Try {
    val lengthBytes = util.Arrays.copyOfRange(bytes, 0, DataLength)
    val length = Ints.fromByteArray(lengthBytes)

    assert(bytes.length == DataLength + (length * (AddressLength + PortLength)), "Data does not match length")

    KnownPeers((0 until length).map { i =>
      val position = lengthBytes.length + (i * (AddressLength + PortLength))
      val addressBytes = util.Arrays.copyOfRange(bytes, position, position + AddressLength)
      val address = InetAddress.getByAddress(addressBytes)
      val portBytes = util.Arrays.copyOfRange(bytes, position + AddressLength, position + AddressLength + PortLength)
      new InetSocketAddress(address, Ints.fromByteArray(portBytes))
    })
  }

  override def serializeData(peers: KnownPeers): Array[Byte] = {
    val length = peers.peers.size
    val lengthBytes = Ints.toByteArray(length)

    peers.peers.foldLeft(lengthBytes) { case (bs, peer) =>
      Bytes.concat(bs, peer.getAddress.getAddress, Ints.toByteArray(peer.getPort))
    }
  }
}

trait SignaturesSeqSpec[A <: AnyRef] extends MessageSpec[A] {

  import vsys.blockchain.transaction.TransactionParser.SignatureLength

  private val DataLength = 4

  def wrap(signatures: Seq[Signature]): A
  def unwrap(v: A): Seq[Signature]


  override def maxLength: Int = DataLength + (200 * SignatureLength)

  override def deserializeData(bytes: Array[Byte]): Try[A] = Try {
    val lengthBytes = bytes.take(DataLength)
    val length = Ints.fromByteArray(lengthBytes)

    assert(bytes.length == DataLength + (length * SignatureLength), "Data does not match length")

    wrap((0 until length).map { i =>
      val position = DataLength + (i * SignatureLength)
      bytes.slice(position, position + SignatureLength)
    })
  }

  override def serializeData(v: A): Array[Byte] = {
    val signatures = unwrap(v)
    val length = signatures.size
    val lengthBytes = Ints.toByteArray(length)

    //WRITE SIGNATURES
    signatures.foldLeft(lengthBytes) { case (bs, header) => Bytes.concat(bs, header) }
  }
}

object GetSignaturesSpec extends SignaturesSeqSpec[GetSignatures] {
  override def wrap(signatures: Seq[Signature]) = GetSignatures(signatures.map(ByteStr(_)))
  override def unwrap(v: GetSignatures) = v.signatures.map(_.arr)

  override val messageCode: MessageCode = 20: Byte
  override val messageName: String = "GetSignatures message"
}

object SignaturesSpec extends SignaturesSeqSpec[Signatures] {
  override def wrap(signatures: Seq[Signature]) = Signatures(signatures.map(ByteStr(_)))
  override def unwrap(v: Signatures) = v.signatures.map(_.arr)

  override val messageCode: MessageCode = 21: Byte
  override val messageName: String = "Signatures message"
}

object GetBlockSpec extends MessageSpec[GetBlock] {
  override val messageCode: MessageCode = 22: Byte
  override val messageName: String = "GetBlock message"


  override def maxLength: Int = TransactionParser.SignatureLength

  override def serializeData(signature: GetBlock): Array[Byte] = signature.signature.arr

  override def deserializeData(bytes: Array[Byte]): Try[GetBlock] = Try {
    require(bytes.length == maxLength, "Data does not match length")
    GetBlock(ByteStr(bytes))
  }
}

object BlockMessageSpec extends MessageSpec[Block] {
  override val messageCode: MessageCode = 23: Byte

  override val messageName: String = "Block message"

  override def maxLength: Int = 271 + TransactionMessageSpec.maxLength * 255

  override def serializeData(block: Block): Array[Byte] = block.bytes

  override def deserializeData(bytes: Array[Byte]): Try[Block] = Block.parseBytes(bytes)
}

object ScoreMessageSpec extends MessageSpec[History.BlockchainScore] {
  override val messageCode: MessageCode = 24: Byte

  override val messageName: String = "Score message"

  override def maxLength: Int = 64 // allows representing scores as high as 6.6E153

  override def serializeData(score: History.BlockchainScore): Array[Byte] = {
    val scoreBytes = score.toByteArray
    val bb = java.nio.ByteBuffer.allocate(scoreBytes.length)
    bb.put(scoreBytes)
    bb.array()
  }

  override def deserializeData(bytes: Array[Byte]): Try[History.BlockchainScore] = Try {
    BigInt(1, bytes)
  }
}

object CheckpointMessageSpec extends MessageSpec[Checkpoint] {
  override val messageCode: MessageCode = 100: Byte

  override val messageName: String = "Checkpoint message"

  private val HeightLength = Ints.BYTES

  override def maxLength: Int = 4 + Checkpoint.MaxCheckpoints * (HeightLength + SignatureLength)

  override def serializeData(checkpoint: Checkpoint): Array[Byte] =
    Bytes.concat(checkpoint.toSign, checkpoint.signature)

  override def deserializeData(bytes: Array[Byte]): Try[Checkpoint] = Try {
    val lengthBytes = util.Arrays.copyOfRange(bytes, 0, Ints.BYTES)
    val length = Ints.fromByteArray(lengthBytes)

    require(length <= Checkpoint.MaxCheckpoints)

    val items = (0 until length).map { i =>
      val position = lengthBytes.length + (i * (HeightLength + SignatureLength))
      val heightBytes = util.Arrays.copyOfRange(bytes, position, position + HeightLength)
      val height = Ints.fromByteArray(heightBytes)
      val blockSignature = util.Arrays.copyOfRange(bytes, position + HeightLength, position + HeightLength + SignatureLength)
      BlockCheckpoint(height, blockSignature)
    }

    val signature = bytes.takeRight(SignatureLength)

    Checkpoint(items, signature)
  }
}

object TransactionMessageSpec extends MessageSpec[Transaction] {
  override val messageCode: MessageCode = 25: Byte

  override val messageName: String = "Transaction message"

  override val maxLength = 4096

  override def deserializeData(bytes: Array[Byte]): Try[Transaction] =
    TransactionParser.parseBytes(bytes)

  override def serializeData(tx: Transaction): Array[Byte] = tx.bytes
}



object BasicMessagesRepo {
  val specs: Seq[MessageSpec[_ <: AnyRef]] = Seq(GetPeersSpec, PeersSpec, GetSignaturesSpec, SignaturesSpec,
    GetBlockSpec, BlockMessageSpec, ScoreMessageSpec, CheckpointMessageSpec, TransactionMessageSpec)
}