package vsys.blockchain.state.diffs

import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import vsys.blockchain.block.TestBlock
import vsys.blockchain.state.EitherExt2
import vsys.blockchain.transaction.lease.LeaseTransaction
import vsys.blockchain.transaction.{GenesisTransaction, PaymentTransaction, TransactionGen}

class BalanceDiffValidationTest extends PropSpec with PropertyChecks with GeneratorDrivenPropertyChecks with Matchers with TransactionGen {

  private implicit def noShrink[A]: Shrink[A] = Shrink(_ => Stream.empty)

  property("disallows overflow") {
    val preconditionsAndPayment: Gen[(GenesisTransaction, GenesisTransaction, PaymentTransaction, PaymentTransaction)] = for {
      master <- accountGen
      master2 <- accountGen
      recipient <- otherAccountGen(candidate = master)
      ts <- timestampGen
      gen1: GenesisTransaction = GenesisTransaction.create(master, Long.MaxValue - 1, -1, ts).explicitGet()
      gen2: GenesisTransaction = GenesisTransaction.create(master2, Long.MaxValue - 1, -1, ts).explicitGet()
      fee <- smallFeeGen
      feeScale <- feeScaleGen
      attachment <- attachmentGen
      amount <- Gen.choose(Long.MaxValue / 2, Long.MaxValue - fee - 1)
      transfer1 = PaymentTransaction.create(master, recipient, amount, fee, feeScale, ts, attachment).explicitGet()
      transfer2 = PaymentTransaction.create(master2, recipient, amount, fee, feeScale, ts + 1, attachment).explicitGet()
    } yield (gen1, gen2, transfer1, transfer2)


    forAll(preconditionsAndPayment) { case ((gen1, gen2, transfer1, transfer2)) =>
      assertDiffEi(Seq(TestBlock.create(Seq(gen1, gen2, transfer1))), TestBlock.create(Seq(transfer2))) { blockDiffEi =>
        blockDiffEi should produce("negative vsys balance")
      }
    }
  }

  property("disallows lease overflow") {
    val setup = for {
      master1 <- accountGen
      master2 <- accountGen
      recipient <- accountGen
      ts <- timestampGen
      gen1 = GenesisTransaction.create(master1, Long.MaxValue - 1, -1, ts).explicitGet()
      gen2 = GenesisTransaction.create(master2, Long.MaxValue - 1, -1, ts).explicitGet()
      fee <- smallFeeGen
      feeScale <-feeScaleGen
      amt1 <- Gen.choose(Long.MaxValue / 2 + 1, Long.MaxValue - 1 - fee)
      amt2 <- Gen.choose(Long.MaxValue / 2 + 1, Long.MaxValue - 1 - fee)
      l1 = LeaseTransaction.create(master1, amt1, fee, feeScale, ts, recipient).explicitGet()
      l2 = LeaseTransaction.create(master2, amt2, fee, feeScale, ts, recipient).explicitGet()
    } yield (gen1, gen2, l1, l2)

    forAll(setup) { case (gen1, gen2, l1, l2) =>
      assertDiffEi(Seq(TestBlock.create(Seq(gen1, gen2, l1))), TestBlock.create(Seq(l2)))(totalDiffEi =>
        totalDiffEi should produce("negative effective balance"))
    }
  }


  val ownLessThatLeaseOut: Gen[(GenesisTransaction, PaymentTransaction, LeaseTransaction, LeaseTransaction, PaymentTransaction)] = for {
    master <- accountGen
    alice <- accountGen
    bob <- accountGen
    cooper <- accountGen
    ts <- positiveIntGen
    amt <- positiveLongGen
    fee <- smallFeeGen
    feeScale <- positiveShortGen
    attachment <- attachmentGen
    genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, -1, ts).explicitGet()
    masterTransfersToAlice: PaymentTransaction = PaymentTransaction.create(master, alice, amt, fee, 100, ts, attachment).explicitGet()
    (aliceLeasesToBob, _) <- leaseAndCancelGeneratorP(alice, bob) suchThat (_._1.amount < amt)
    (masterLeasesToAlice, _) <- leaseAndCancelGeneratorP(master, alice) suchThat (_._1.amount > aliceLeasesToBob.amount)
    transferAmt <- Gen.choose(amt - fee - aliceLeasesToBob.amount, amt - fee)
    aliceTransfersMoreThanOwnsMinusLeaseOut = PaymentTransaction.create(alice, cooper, transferAmt, fee, 100, ts, attachment).explicitGet()

  } yield (genesis, masterTransfersToAlice, aliceLeasesToBob, masterLeasesToAlice, aliceTransfersMoreThanOwnsMinusLeaseOut)

}
