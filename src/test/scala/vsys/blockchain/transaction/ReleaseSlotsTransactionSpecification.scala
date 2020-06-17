package vsys.blockchain.transaction

import org.scalatest._
import org.scalatest.prop.PropertyChecks
import vsys.blockchain.transaction.TransactionParser.TransactionType
import vsys.blockchain.transaction.spos.ReleaseSlotsTransaction

class ReleaseSlotsTransactionSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen {

  property("ReleaseSlotstransaction serialization roundtrip") {
    forAll(releaseSlotsGen) { tx: ReleaseSlotsTransaction =>
      require(tx.bytes.head == TransactionType.ReleaseSlotsTransaction.id)
      val recovered = ReleaseSlotsTransaction.parseTail(tx.bytes.tail).get
      assertTxs(recovered, tx)
    }
  }

  property("ReleaseSlotstransaction serialization from TypedTransaction") {
    forAll(releaseSlotsGen) { tx: ReleaseSlotsTransaction =>
      val recovered = TransactionParser.parseBytes(tx.bytes).get
      assertTxs(recovered.asInstanceOf[ReleaseSlotsTransaction], tx)
    }
  }

  private def assertTxs(first: ReleaseSlotsTransaction, second: ReleaseSlotsTransaction): Unit = {
    first.proofs.bytes shouldEqual second.proofs.bytes
    first.timestamp shouldEqual second.timestamp
    first.transactionFee shouldEqual second.transactionFee
    first.feeScale shouldEqual second.feeScale
    first.slotId shouldEqual second.slotId
    first.bytes shouldEqual second.bytes
  }
}
