package vsys.blockchain

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

import kamon.Kamon
import kamon.metric.instrument
import vsys.account.PublicKeyAccount
import vsys.blockchain.block.Block
import vsys.blockchain.consensus.SPoSCalc._
import vsys.blockchain.consensus.TransactionsOrdering
import vsys.blockchain.history.{History, CheckpointService}
import vsys.blockchain.mining.Miner
import vsys.blockchain.state.ByteStr
import vsys.blockchain.state.reader.StateReader
import vsys.blockchain.transaction.ValidationError.GenericError
import vsys.blockchain.transaction._
import vsys.network.{BlockCheckpoint, Checkpoint}
import vsys.settings.{BlockchainSettings, VsysSettings}
import vsys.utils.{ScorexLogging, Time}

object Coordinator extends ScorexLogging {
  def processFork(checkpoint: CheckpointService, history: History, blockchainUpdater: BlockchainUpdater, stateReader: StateReader,
                  utxStorage: UtxPool, time: Time, settings: VsysSettings, miner: Miner, blockchainReadiness: AtomicBoolean)
                 (newBlocks: Seq[Block]): Either[ValidationError, BigInt] = {
    val extension = newBlocks.dropWhile(history.contains)

    extension.headOption.map(_.reference) match {
      case Some(lastCommonBlockId) =>

        def isForkValidWithCheckpoint(lastCommonHeight: Int): Boolean =
          extension.zipWithIndex.forall(p => checkpoint.isBlockValid(p._1.signerData.signature, lastCommonHeight + 1 + p._2))

        def forkApplicationResultEi: Either[ValidationError, BigInt] = extension.view
          .map(b => b -> appendBlock(checkpoint, history, blockchainUpdater, stateReader, utxStorage, time, settings.blockchainSettings)(b))
          .collectFirst { case (b, Left(e)) => b -> e }
          .fold[Either[ValidationError, BigInt]](Right(history.score())) {
          case (b, e) =>
            log.warn(s"Can't process fork starting with $lastCommonBlockId, error appending block ${b.uniqueId}: $e")
            Left(e)
        }

        for {
          commonBlockHeight <- history.heightOf(lastCommonBlockId).toRight(GenericError("Fork contains no common parent"))
          _ <- Either.cond(isForkValidWithCheckpoint(commonBlockHeight), (), GenericError("Fork contains block that doesn't match checkpoint, declining fork"))
          droppedTransactions <- blockchainUpdater.removeAfter(lastCommonBlockId)
          score <- forkApplicationResultEi
        } yield {
          droppedTransactions.foreach(utxStorage.putIfNew)
          miner.lastBlockChanged()
          updateBlockchainReadinessFlag(history, time, blockchainReadiness, settings.minerSettings.intervalAfterLastBlockThenGenerationIsAllowed)
          score
        }
      case None =>
        log.debug("No new blocks found in extension")
        Right(history.score())
    }
  }


  private def updateBlockchainReadinessFlag(history: History, time: Time, blockchainReadiness: AtomicBoolean, maxBlockchainAge: Duration): Boolean = {
    val expired = time.correctedTime() - history.lastBlock.get.timestamp < maxBlockchainAge.toNanos
    blockchainReadiness.compareAndSet(expired, !expired)
  }

  def processBlock(checkpoint: CheckpointService, history: History, blockchainUpdater: BlockchainUpdater, time: Time,
                   stateReader: StateReader, utxStorage: UtxPool, blockchainReadiness: AtomicBoolean, miner: Miner,
                   settings: VsysSettings)(newBlock: Block, local: Boolean): Either[ValidationError, BigInt] = {
    val newScore = for {
      _ <- appendBlock(checkpoint, history, blockchainUpdater, stateReader, utxStorage, time, settings.blockchainSettings)(newBlock)
    } yield history.score()

    if (local || newScore.isRight) {
      updateBlockchainReadinessFlag(history, time, blockchainReadiness, settings.minerSettings.intervalAfterLastBlockThenGenerationIsAllowed)
      miner.lastBlockChanged()
    }
    newScore
  }

  private def appendBlock(checkpoint: CheckpointService, history: History, blockchainUpdater: BlockchainUpdater,
                          stateReader: StateReader, utxStorage: UtxPool, time: Time, settings: BlockchainSettings)
                         (block: Block): Either[ValidationError, Unit] = for {
    _ <- Either.cond(checkpoint.isBlockValid(block.signerData.signature, history.height() + 1), (),
      GenericError(s"Block ${block.uniqueId} at height ${history.height() + 1} is not valid w.r.t. checkpoint"))
    _ <- blockConsensusValidation(history, stateReader, settings, time.correctedTime())(block)
    _ <- blockchainUpdater.processBlock(block)
  } yield utxStorage.removeAll(block.transactionData.map(_.transaction))
  // TODO: change utxStorage to use ProcessedTransaction

  def processCheckpoint(checkpoint: CheckpointService, history: History, blockchainUpdater: BlockchainUpdater)
                       (newCheckpoint: Checkpoint): Either[ValidationError, BigInt] =
    checkpoint.set(newCheckpoint).map { _ =>
      makeBlockchainCompliantWith(history, blockchainUpdater)(newCheckpoint)
      history.score()
    }


  private def makeBlockchainCompliantWith(history: History, blockchainUpdater: BlockchainUpdater)(checkpoint: Checkpoint): Unit = {
    val existingItems = checkpoint.items.filter {
      checkpoint => history.blockAt(checkpoint.height).isDefined
    }

    val fork = existingItems.takeWhile {
      case BlockCheckpoint(h, sig) =>
        val block = history.blockAt(h).get
        block.signerData.signature != ByteStr(sig)
    }

    if (fork.nonEmpty) {
      val genesisBlockHeight = 1
      val hh = existingItems.map(_.height) :+ genesisBlockHeight
      history.blockAt(hh(fork.size)).foreach {
        lastValidBlock =>
          log.warn(s"Fork detected (length = ${fork.size}), rollback to last valid block id [${lastValidBlock.uniqueId}]")
          blockchainUpdater.removeAfter(lastValidBlock.uniqueId)
      }
    }
  }

  val MaxTimeDrift: Long = Duration.ofSeconds(15).toNanos
  val MaxMintTimeFromFuture: Long = Duration.ofMillis(30000).toNanos
  private val blockReceiveGapStats = Kamon.metrics.histogram("block-receive-gap", instrument.Time.Milliseconds)

  private def blockConsensusValidation(history: History, state: StateReader, bcs: BlockchainSettings, currentTs: Long)
                                      (block: Block): Either[ValidationError, Unit] = {

    val fs = bcs.functionalitySettings
    val sortEnd = Long.MaxValue
    val blockTime = block.timestamp
    val mt = block.consensusData.mintTime

    blockReceiveGapStats.record(Math.abs(currentTs - mt) / 1000000L)

    (for {
      _ <- Either.cond(blockTime - currentTs < MaxTimeDrift, (), s"timestamp $blockTime is from future")
      // use same ordering
      /*
      _ <- Either.cond(blockTime > sortEnd ||
        block.transactionData.map(_.transaction).sorted(TransactionsOrdering.InUTXPool) == block.transactionData.map(_.transaction),
        (), "transactions are not sorted")
      */
      // if minting tx is the last tx in block, just drop it
      _ <- Either.cond(blockTime > sortEnd ||
        block.transactionData.map(_.transaction).dropRight(1).sorted(TransactionsOrdering.InUTXPool) == block.transactionData.map(_.transaction).dropRight(1),
        (), "transactions are not sorted")

      parent <- history.parent(block).toRight(s"history does not contain parent ${block.reference}")
      parentHeight <- history.heightOf(parent.uniqueId).toRight(s"history does not contain parent ${block.reference}")
      prevBlockData = parent.consensusData
      blockData = block.consensusData

      generator = block.signerData.generator

      //check generator.address, compare mintTime and generator's slot id
      minterAddress = PublicKeyAccount.toAddress(generator)
      mintTime = block.consensusData.mintTime
      slotid = (mintTime / 1000000000L / Math.max(fs.mintingSpeed, 1L)) % fs.numOfSlots
      slotAddress = state.slotAddress(slotid.toInt)
      _ <- Either.cond(slotid.toInt % SlotGap == 0, (), ValidationError.InvalidSlotId(slotid.toInt))
      _ <- Either.cond(minterAddress.address == slotAddress.get, (), s"Minting address ${minterAddress.address} does not match the slot address ${slotAddress.get} of slot $slotid")


      // TODO
      // set a better duration here
      // compare cntTime and mintTime
      // commit this validation first
      //_ <- Either.cond(Math.abs(currentTs-mintTime) < MaxBlockTimeRange, (), s"Block too old or from future, current time ${currentTs}, mint time ${mintTime}")

      mintBalance = block.consensusData.mintBalance
      checkedMintBalance = mintingBalance(state, fs, generator.toAddress, state.height)
      _ <- Either.cond(mintBalance == checkedMintBalance, (), s"Block minting average balance $mintBalance is not equal to exact $checkedMintBalance")

      // check mint time is larger than parent block mint time
      _ <- Either.cond(mintTime > prevBlockData.mintTime, (), s"Block mint time $mintTime is not larger than parent mint time ${prevBlockData.mintTime}")

      // mint time should not greater than current time + 30s(error in one round)
       _ <- Either.cond(currentTs + MaxMintTimeFromFuture >= mintTime, (), s"Block from future, current time $currentTs, mint time $mintTime")

      _ <- Either.cond(block.transactionData.map(_.transaction).filter(_.transactionType == TransactionParser.TransactionType.MintingTransaction).size == 1,
           (), s"One and only one minting transaction allowed per block" )
    } yield ()).left.map(e => GenericError(s"Block ${block.uniqueId} is invalid: $e"))
  }

}
