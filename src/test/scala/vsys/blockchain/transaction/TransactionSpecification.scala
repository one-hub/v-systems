package vsys.blockchain.transaction

import java.security.SecureRandom

import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
import vsys.account.PrivateKeyAccount
import vsys.blockchain.state.EitherExt2
import vsys.blockchain.transaction.TransactionParser.TransactionType

import scala.util.{Failure, Try}


class TransactionSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen {

  def parseBytes(data: Array[Byte]): Try[PaymentTransaction] = {
    data.head match {
      case transactionType: Byte if transactionType == TransactionType.PaymentTransaction.id =>
        PaymentTransaction.parseTail(data.tail)
      case transactionType =>
        Failure(new Exception(s"Incorrect transaction type '$transactionType' in PaymentTransaction data"))
    }
  }

  property("transaction fields should be constructed in a right way") {
    forAll(bytes32gen, bytes32gen, timestampGen, positiveLongGen, positiveLongGen, feeScaleGen) {
      (senderSeed: Array[Byte], recipientSeed: Array[Byte], time: Long, amount: Long, fee: Long, feeScale: Short) =>

        val sender = PrivateKeyAccount(senderSeed)
        val recipient = PrivateKeyAccount(recipientSeed)
        val attachment = Array.fill(new SecureRandom().nextInt(PaymentTransaction.MaxAttachmentSize))((new SecureRandom().nextInt(256) - 128).toByte)

        val tx = PaymentTransaction.create(sender, recipient, amount, fee, feeScale, time, attachment).explicitGet()

        tx.timestamp shouldEqual time
        tx.amount shouldEqual amount
        tx.transactionFee shouldEqual fee
        tx.feeScale shouldEqual feeScale
        tx.attachment shouldEqual attachment
        tx.recipient.address shouldEqual recipient.address
    }
  }

  property("bytes()/parse() roundtrip should preserve a transaction") {
    forAll(bytes32gen, bytes32gen, timestampGen, positiveLongGen, positiveLongGen, feeScaleGen) {
      (senderSeed: Array[Byte], recipientSeed: Array[Byte], time: Long, amount: Long, fee: Long, feeScale: Short) =>

        val sender = PrivateKeyAccount(senderSeed)
        val recipient = PrivateKeyAccount(recipientSeed)
        val attachment = Array.fill(new SecureRandom().nextInt(PaymentTransaction.MaxAttachmentSize))((new SecureRandom().nextInt(256) - 128).toByte)

        val tx = PaymentTransaction.create(sender, recipient, amount, fee, feeScale, time, attachment).explicitGet()
        val txAfter = parseBytes(tx.bytes).get

        txAfter.getClass.shouldBe(tx.getClass)

        tx.recipient.address shouldEqual txAfter.recipient.address
        tx.timestamp shouldEqual txAfter.timestamp
        tx.attachment shouldEqual attachment
        tx.amount shouldEqual txAfter.amount
        tx.transactionFee shouldEqual txAfter.transactionFee
        tx.feeScale shouldEqual txAfter.feeScale
        tx.proofs shouldEqual txAfter.proofs
    }
  }

  property("PaymentTransaction should deserialize to LagonakiTransaction") {
    forAll(bytes32gen, bytes32gen, timestampGen, positiveLongGen, positiveLongGen, feeScaleGen) {
      (senderSeed: Array[Byte], recipientSeed: Array[Byte], time: Long, amount: Long, fee: Long, feeScale: Short) =>

        val sender = PrivateKeyAccount(senderSeed)
        val recipient = PrivateKeyAccount(recipientSeed)
        val attachment = Array.fill(new SecureRandom().nextInt(PaymentTransaction.MaxAttachmentSize))((new SecureRandom().nextInt(256) - 128).toByte)
        val tx = PaymentTransaction.create(sender, recipient, amount, fee, feeScale, time, attachment).explicitGet()
        val txAfter = TransactionParser.parseBytes(tx.bytes).get.asInstanceOf[PaymentTransaction]

        txAfter.getClass.shouldBe(tx.getClass)

        tx.recipient.address shouldEqual txAfter.recipient.address
        tx.timestamp shouldEqual txAfter.timestamp
        tx.attachment shouldEqual txAfter.attachment
        tx.amount shouldEqual txAfter.amount
        tx.transactionFee shouldEqual txAfter.transactionFee
        tx.feeScale shouldEqual txAfter.feeScale
        tx.proofs shouldEqual txAfter.proofs
    }
  }

}