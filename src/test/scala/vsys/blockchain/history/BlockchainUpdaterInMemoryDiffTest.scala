package vsys.blockchain.history

import vsys.blockchain.state._
import vsys.blockchain.state.diffs._
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.prop.PropertyChecks
import vsys.blockchain.transaction._
import vsys.blockchain.state.diffs.CommonValidation.MaxTimeTransactionOverBlockDiff

class BlockchainUpdaterInMemoryDiffTest extends PropSpec with PropertyChecks with DomainScenarioDrivenPropertyCheck with Matchers with TransactionGen {
  val preconditionsAndPayments: Gen[(GenesisTransaction, PaymentTransaction, PaymentTransaction)] = for {
    master <- accountGen
    recipient <- accountGen
    //just because the block time is 0 in this test case. Need refactoring later.
    ts <- Gen.choose(1, MaxTimeTransactionOverBlockDiff.toNanos - 1)
    genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, -1, ts).explicitGet()
    payment: PaymentTransaction <- paymentGeneratorP(ts, master, recipient)
    payment2: PaymentTransaction <- paymentGeneratorP(ts, master, recipient)
  } yield (genesis, payment, payment2)

  property("compactification test") {

    scenario(preconditionsAndPayments) { case (domain, (genesis, payment1, payment2)) =>
      val blocksWithoutCompactification = chainBlocks(
        Seq(genesis) +:
          Seq.fill(MinInMemoryDiffSize * 2 - 1)(Seq.empty[Transaction]) :+
          Seq(payment1))
      val blockTriggersCompactification = buildBlockOfTxs(blocksWithoutCompactification.last.uniqueId, Seq(payment2))

      blocksWithoutCompactification.foreach(b => domain.blockchainUpdater.processBlock(b).explicitGet())
      val mastersBalanceAfterPayment1 = domain.stateReader.accountPortfolio(genesis.recipient).balance
      mastersBalanceAfterPayment1 shouldBe (ENOUGH_AMT - payment1.amount - payment1.transactionFee)

      domain.history.height() shouldBe MinInMemoryDiffSize * 2 + 1
      domain.stateReader.height shouldBe MinInMemoryDiffSize * 2 + 1

      domain.blockchainUpdater.processBlock(blockTriggersCompactification).explicitGet()

      domain.history.height() shouldBe MinInMemoryDiffSize * 2 + 2
      domain.stateReader.height shouldBe MinInMemoryDiffSize * 2 + 2

      val mastersBalanceAfterPayment1AndPayment2 = domain.stateReader.accountPortfolio(genesis.recipient).balance
      mastersBalanceAfterPayment1AndPayment2 shouldBe (ENOUGH_AMT - payment1.amount - payment1.transactionFee - payment2.amount - payment2.transactionFee)
    }
  }
  property("compactification after rollback test") {
    scenario(preconditionsAndPayments) { case (domain, (genesis, payment1, payment2)) =>
      val firstBlocks = chainBlocks(Seq(Seq(genesis)) ++ Seq.fill(MinInMemoryDiffSize * 2 - 2)(Seq.empty[Transaction]))
      val payment1Block = buildBlockOfTxs(firstBlocks.last.uniqueId, Seq(payment1))
      val emptyBlock = buildBlockOfTxs(payment1Block.uniqueId, Seq.empty)
      val blockTriggersCompactification = buildBlockOfTxs(payment1Block.uniqueId, Seq(payment2))

      firstBlocks.foreach(b => domain.blockchainUpdater.processBlock(b).explicitGet())
      domain.blockchainUpdater.processBlock(payment1Block).explicitGet()
      domain.blockchainUpdater.processBlock(emptyBlock).explicitGet()
      val mastersBalanceAfterPayment1 = domain.stateReader.accountPortfolio(genesis.recipient).balance
      mastersBalanceAfterPayment1 shouldBe (ENOUGH_AMT - payment1.amount - payment1.transactionFee)

      // discard liquid block
      domain.blockchainUpdater.removeAfter(payment1Block.uniqueId)
      domain.blockchainUpdater.processBlock(blockTriggersCompactification).explicitGet()

      domain.history.height() shouldBe MinInMemoryDiffSize * 2 + 1
      domain.stateReader.height shouldBe MinInMemoryDiffSize * 2 + 1

      val mastersBalanceAfterPayment1AndPayment2 = domain.stateReader.accountPortfolio(genesis.recipient).balance
      mastersBalanceAfterPayment1AndPayment2 shouldBe (ENOUGH_AMT - payment1.amount - payment1.transactionFee - payment2.amount - payment2.transactionFee)
    }
  }
}
