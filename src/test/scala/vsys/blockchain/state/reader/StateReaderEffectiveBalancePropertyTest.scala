package vsys.blockchain.state.reader

import vsys.blockchain.state.diffs.{ENOUGH_AMT, assertDiffAndState}
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import vsys.blockchain.block.TestBlock
import vsys.blockchain.state.EitherExt2
import vsys.blockchain.transaction.{GenesisTransaction, TransactionGen}


class StateReaderEffectiveBalancePropertyTest extends PropSpec with PropertyChecks with GeneratorDrivenPropertyChecks with Matchers with TransactionGen {
  private implicit def noShrink[A]: Shrink[A] = Shrink(_ => Stream.empty)

  val setup: Gen[(GenesisTransaction, Int, Int, Int)] = for {
    master <- accountGen
    ts <- positiveIntGen
    genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, -1, ts).explicitGet()
    emptyBlocksAmt <- Gen.choose(1, 10)
    atHeight <- Gen.choose(0, 20)
    confirmations <- Gen.choose(1, 20)
  } yield (genesis, emptyBlocksAmt, atHeight, confirmations)


  property("No-interactions genesis account's effectiveBalance doesn't depend on depths") {
    forAll(setup) { case ((genesis: GenesisTransaction, emptyBlocksAmt, atHeight, confirmations)) =>
      val genesisBlock = TestBlock.create(Seq(genesis))
      val nextBlocks = List.fill(emptyBlocksAmt - 1)(TestBlock.create(Seq.empty))
      assertDiffAndState(genesisBlock +: nextBlocks, TestBlock.create(Seq.empty)) { (_, newState) =>
        newState.effectiveBalanceAtHeightWithConfirmations(genesis.recipient, atHeight, confirmations) shouldBe genesis.amount
      }
    }
  }
}