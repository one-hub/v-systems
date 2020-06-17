package vsys.blockchain.state.diffs

import cats.Monoid
import vsys.blockchain.state._
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import vsys.blockchain.block.TestBlock
import vsys.blockchain.transaction.{GenesisTransaction, TransactionGen}
import vsys.blockchain.transaction.database.DbPutTransaction

class DbTransactionDiffTest extends PropSpec with PropertyChecks with GeneratorDrivenPropertyChecks with Matchers with TransactionGen {

  private implicit def noShrink[A]: Shrink[A] = Shrink(_ => Stream.empty)

  val preconditionsAndDbPut: Gen[(GenesisTransaction, DbPutTransaction)] = for {
    sender <- accountGen
    ts <- positiveIntGen
    fee: Long <- smallFeeGen
    genesis: GenesisTransaction = GenesisTransaction.create(sender, ENOUGH_AMT, -1, ts).explicitGet()
    tx: DbPutTransaction <- dbPutGeneratorP(ts, sender, fee)
  } yield (genesis, tx)

  val preconditionsWithoutEnoughAmtAndDbPut: Gen[(GenesisTransaction, DbPutTransaction)] = for {
    sender <- accountGen
    ts <- positiveIntGen
    fee: Long <- smallFeeGen
    genesis: GenesisTransaction = GenesisTransaction.create(sender, fee / 2, -1, ts).explicitGet()
    tx: DbPutTransaction <- dbPutGeneratorP(ts, sender, fee)
  } yield (genesis, tx)


  property("Diff doesn't break invariant") {
    forAll(preconditionsAndDbPut) { case (genesis, dbPutTx: DbPutTransaction) =>
      assertDiffAndState(Seq(TestBlock.create(Seq(genesis))), TestBlock.create(Seq(dbPutTx))) { (blockDiff, newState) =>
        val totalPortfolioDiff: Portfolio = Monoid.combineAll(blockDiff.txsDiff.portfolios.values)
        totalPortfolioDiff.balance shouldBe -dbPutTx.transactionFee
        totalPortfolioDiff.effectiveBalance shouldBe -dbPutTx.transactionFee
        totalPortfolioDiff.spendableBalance shouldBe -dbPutTx.transactionFee
        val sender = dbPutTx.proofs.firstCurveProof.explicitGet().publicKey
        val (_, txs) = newState.accountTransactionIds(sender, 2, 0)
        txs.size shouldBe 2 // genesis and dbPut transaction
      }
    }
  }

  property("Insufficient amount") {
    forAll(preconditionsWithoutEnoughAmtAndDbPut) { case (genesis, dbPutTx) =>
      assertDiffEi(Seq(TestBlock.create(Seq(genesis))), TestBlock.create(Seq(dbPutTx))) { blockDiffEi =>
        blockDiffEi should produce("negative vsys balance")
      }
    }
  }
}
