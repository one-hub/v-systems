package vsys.blockchain.state.reader

import java.util.concurrent.locks.ReentrantReadWriteLock

import vsys.blockchain.state.StateStorage
import vsys.blockchain.state.StateStorage._
import org.scalatest.{Matchers, Outcome, fixture}
import vsys.account.Address
import vsys.db.openDB

class StateReaderEffectiveBalanceTest extends fixture.FunSuite with Matchers {

  val acc: Address = Address.fromPublicKey(Array.emptyByteArray)
  val stateHeight = 100

  private val db = openDB("./test/balance/data", true)
  
  override type FixtureParam = StateStorage

  override protected def withFixture(test: OneArgTest): Outcome = {
    val storage = StateStorage(db, dropExisting = false)
    storage.setHeight(stateHeight)
    test(storage)
  }

  // ALL test here just set weightedBalance = 0
  // TODO: test for weightedBalance

  test("exposes minimum of all 'current' and  one 'previous' of oldest record") { storage =>
    storage.balanceSnapshots.put(accountIndexKey(acc, 20), (0, 0, 1, 0))
    storage.balanceSnapshots.put(accountIndexKey(acc, 75), (20, 0, 200, 0))
    storage.balanceSnapshots.put(accountIndexKey(acc, 90), (75, 0, 100, 0))
    storage.lastBalanceSnapshotHeight.put(acc.bytes, 90)

    new StateReaderImpl(storage, new ReentrantReadWriteLock()).effectiveBalanceAtHeightWithConfirmations(acc, stateHeight, 50) shouldBe 1
  }

  test("exposes current effective balance if no records in past N blocks are made") { storage =>
    storage.balanceSnapshots.put(accountIndexKey(acc, 20), (0, 0, 1, 0))
    storage.portfolios.put(acc.bytes, (1, (0, 0), Map.empty))
    storage.lastBalanceSnapshotHeight.put(acc.bytes, 20)

    new StateReaderImpl(storage, new ReentrantReadWriteLock()).effectiveBalanceAtHeightWithConfirmations(acc, stateHeight, 50) shouldBe 1
  }

  test("doesn't include info older than N blocks") { storage =>
    storage.balanceSnapshots.put(accountIndexKey(acc, 20), (0, 0, 1000, 0))
    storage.balanceSnapshots.put(accountIndexKey(acc, 50), (20, 0, 50000, 0))
    storage.balanceSnapshots.put(accountIndexKey(acc, 75), (50, 0, 100000, 0))
    storage.lastBalanceSnapshotHeight.put(acc.bytes, 75)

    new StateReaderImpl(storage, new ReentrantReadWriteLock()).effectiveBalanceAtHeightWithConfirmations(acc, stateHeight, 50) shouldBe 50000
  }

  test("includes most recent update") { storage =>
    storage.balanceSnapshots.put(accountIndexKey(acc, 20), (0, 0, 1000, 0))
    storage.balanceSnapshots.put(accountIndexKey(acc, 51), (20, 0, 50000, 0))
    storage.balanceSnapshots.put(accountIndexKey(acc, 100), (51, 0, 1, 0))
    storage.lastBalanceSnapshotHeight.put(acc.bytes, 100)

    new StateReaderImpl(storage, new ReentrantReadWriteLock()).effectiveBalanceAtHeightWithConfirmations(acc, stateHeight, 50) shouldBe 1
  }

  test("exposes zero if record was made in past N blocks") { storage =>
    storage.balanceSnapshots.put(accountIndexKey(acc, 70), (0, 0, 1000, 0))
    storage.lastBalanceSnapshotHeight.put(acc.bytes, 70)
    new StateReaderImpl(storage, new ReentrantReadWriteLock()).effectiveBalanceAtHeightWithConfirmations(acc, stateHeight, 50) shouldBe 0
  }

  test("exposes zero if no records was made at all") { storage =>
    new StateReaderImpl(storage, new ReentrantReadWriteLock()).effectiveBalanceAtHeightWithConfirmations(acc, stateHeight, 50) shouldBe 0
  }
}