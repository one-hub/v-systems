package vsys.blockchain.state

import java.util.concurrent.locks.ReentrantReadWriteLock

import vsys.blockchain.history.HistoryWriterImpl
import vsys.blockchain.state.reader.{CompositeStateReader, StateReader}
import vsys.blockchain.block.Block
import vsys.blockchain.history.History
import vsys.blockchain.transaction.{Transaction, ValidationError}
import vsys.blockchain.contract.ExecutionContext
import vsys.blockchain.state.opcdiffs.{OpcDiff, OpcFuncDiffer}
import vsys.blockchain.transaction.contract.{ExecuteContractFunctionTransaction, RegisterContractTransaction}
import vsys.blockchain.history.db
import vsys.blockchain.transaction.proof.EllipticCurve25519Proof
import vsys.settings.{FunctionalitySettings, TestFunctionalitySettings, TestStateSettings}

package object diffs {

  private val lock = new ReentrantReadWriteLock()

  def newState(): StateWriterImpl = new StateWriterImpl(StateStorage(db, dropExisting = true), lock, TestStateSettings.AllOn)

  def newHistory(): History = new HistoryWriterImpl(db, lock, true)

  val ENOUGH_AMT: Long = Long.MaxValue / 3

  def assertDiffEi(preconditions: Seq[Block], block: Block, fs: FunctionalitySettings = TestFunctionalitySettings.Enabled)(assertion: Either[ValidationError, BlockDiff] => Unit): Unit = {
    val differ: (StateReader,  Block) => Either[ValidationError, BlockDiff] = (s, b) => BlockDiffer.fromBlock(fs, s, None)(b)

    val state = newState()

    preconditions.foreach { precondition =>
      val preconditionDiff = differ(state,  precondition).explicitGet()
      state.applyBlockDiff(preconditionDiff)
    }
    val totalDiff1 = differ(state,  block)
    assertion(totalDiff1)

    val preconditionDiff = BlockDiffer.unsafeDiffMany(fs, newState(), None)(preconditions)
    val compositeState = new CompositeStateReader(newState(), preconditionDiff)
    val totalDiff2 = differ(compositeState, block)
    assertion(totalDiff2)
  }

  def assertDiffAndState(preconditions: Seq[Block], block: Block, fs: FunctionalitySettings = TestFunctionalitySettings.Enabled)(assertion: (BlockDiff, StateReader) => Unit): Unit = {
    val differ: (StateReader, Block) => Either[ValidationError, BlockDiff] = (s, b) => BlockDiffer.fromBlock(fs, s, None)(b)

    val state = newState()
    preconditions.foreach { precondition =>
      val preconditionDiff = differ(state,  precondition).explicitGet()
      state.applyBlockDiff(preconditionDiff)
    }
    val totalDiff1 = differ(state,  block).explicitGet()
    state.applyBlockDiff(totalDiff1)
    assertion(totalDiff1, state)

    val preconditionDiff = BlockDiffer.unsafeDiffMany(fs, newState(), None)(preconditions)
    val compositeState = new CompositeStateReader(newState(), preconditionDiff)
    val totalDiff2 = differ(compositeState,  block).explicitGet()
    assertion(totalDiff2, new CompositeStateReader(compositeState, totalDiff2))
  }

  def assertDiffAndStateCorrectBlockTime(preconditions: Seq[Block], block: Block, fs: FunctionalitySettings = TestFunctionalitySettings.Enabled)(assertion: (BlockDiff, StateReader) => Unit): Unit = {
    val differ: (StateReader, Block) => Either[ValidationError, BlockDiff] = (s, b) => BlockDiffer.fromBlock(fs, s, Option(b.timestamp - 1))(b)

    val state = newState()
    preconditions.foreach { precondition =>
      val preconditionDiff = differ(state,  precondition).explicitGet()
      state.applyBlockDiff(preconditionDiff)
    }
    val totalDiff1 = differ(state,  block).explicitGet()
    state.applyBlockDiff(totalDiff1)
    assertion(totalDiff1, state)

    val preconditionDiff = BlockDiffer.unsafeDiffMany(fs, newState(), None)(preconditions)
    val compositeState = new CompositeStateReader(newState(), preconditionDiff)
    val totalDiff2 = differ(compositeState,  block).explicitGet()
    assertion(totalDiff2, new CompositeStateReader(compositeState, totalDiff2))
  }

  def produce(errorMessage: String): ProduceError = new ProduceError(errorMessage)

  def assertOpcFuncDifferEi(height: Int, preconditions: Option[RegisterContractTransaction], tx: Transaction,
                            fs: FunctionalitySettings = TestFunctionalitySettings.Enabled)(assertion: Either[ValidationError, OpcDiff] => Unit): Unit = {

    val state = newState()
    if (preconditions.isDefined) {
      val bf = preconditions match {
        case Some(tx) =>
          val txDiffer = RegisterContractTransactionDiff(state, fs, height, Option(0L), 1L)(tx).explicitGet()
          BlockDiff(txDiffer, 1, Map.empty)
        case _ => BlockDiff.empty
      }
      state.applyBlockDiff(bf)
    }

    tx match {
      case tx: RegisterContractTransaction
      => assertion(OpcFuncDiffer(ExecutionContext.fromRegConTx(state, fs, Option(0L), 1L, height, tx).right.get)(tx.data))
      case tx: ExecuteContractFunctionTransaction
      => {
        val signers = tx.proofs.proofs.map(x => EllipticCurve25519Proof.fromBytes(x.bytes.arr).explicitGet().publicKey)
        val contractId = tx.contractId
        val description = tx.attachment
        val c = preconditions.get.contract
        assertion(OpcFuncDiffer(ExecutionContext(signers, state, fs, Option(0L), 1L, height, tx, contractId, c.descriptor(tx.funcIdx), c.stateVar, c.stateMap, description, 0))(tx.data))
      }
    }
  }
}
