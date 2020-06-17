package vsys.network

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.group.ChannelGroup
import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import vsys.blockchain.{BlockchainUpdater, Coordinator, UtxPool}
import vsys.blockchain.mining.Miner
import vsys.blockchain.block.Block
import vsys.blockchain.history.{CheckpointService, History}
import vsys.blockchain.state.reader.StateReader
import vsys.blockchain.transaction.ValidationError.InvalidSignature
import vsys.blockchain.transaction._
import vsys.settings.VsysSettings
import vsys.utils.{ScorexLogging, Time}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Sharable
class CoordinatorHandler(
    checkpointService: CheckpointService,
    history: History,
    blockchainUpdater: BlockchainUpdater,
    time: Time,
    stateReader: StateReader,
    utxStorage: UtxPool,
    blockchainReadiness:
    AtomicBoolean,
    miner: Miner,
    settings: VsysSettings,
    peerDatabase: PeerDatabase,
    allChannels: ChannelGroup)
  extends ChannelInboundHandlerAdapter with ScorexLogging {

  private val counter = new AtomicInteger
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor { r =>
    val t = new Thread(r)
    t.setName(s"coordinator-handler-${counter.incrementAndGet()}")
    t.setDaemon(true)
    t
  })

  private val processCheckpoint = Coordinator.processCheckpoint(checkpointService, history, blockchainUpdater) _
  private val processFork = Coordinator.processFork(checkpointService, history, blockchainUpdater, stateReader, utxStorage, time, settings, miner, blockchainReadiness) _
  private val processBlock = Coordinator.processBlock(checkpointService, history, blockchainUpdater, time, stateReader, utxStorage, blockchainReadiness, miner, settings) _

  private def processAndBlacklistOnFailure(
      src: Channel,
      start: => String,
      success: => String,
      errorPrefix: String,
      f: => Either[_, BigInt]): Unit = {
    log.debug(start)
    Future(f) onComplete {
      case Success(Right(newScore)) =>
        log.debug(success)
        allChannels.broadcast(LocalScoreChanged(newScore))
      case Success(Left(ve)) =>
        log.warn(s"$errorPrefix: $ve")
        peerDatabase.blacklistAndClose(src, s"$errorPrefix: $ve")
      case Failure(t) => rethrow(errorPrefix, t)
    }
  }

  private def rethrow(msg: String, failure: Throwable) = throw new Exception(msg, failure.getCause)
  private def warnAndBlacklist(msg: => String, ch: Channel): Unit = {
    log.warn(msg)
    peerDatabase.blacklistAndClose(ch, msg)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case c: Checkpoint => processAndBlacklistOnFailure(ctx.channel,
      "Attempting to process checkpoint",
      "Successfully processed checkpoint",
      s"Error processing checkpoint",
      processCheckpoint(c))

    case ExtensionBlocks(blocks) => processAndBlacklistOnFailure(ctx.channel(),
      s"Attempting to append extension ${formatBlocks(blocks)}",
      s"Successfully appended extension ${formatBlocks(blocks)}",
      s"Error appending extension ${formatBlocks(blocks)}",
      processFork(blocks))

    case b: Block => Future(Signed.validateSignatures(b).flatMap(b => processBlock(b, false))) onComplete {
      case Success(Right(newScore)) =>
        log.debug(s"Appended block ${b.uniqueId}")
        allChannels.broadcast(LocalScoreChanged(newScore))
      case Success(Left(is: InvalidSignature)) =>
        warnAndBlacklist(s"Could not append block ${b.uniqueId}: $is", ctx.channel())
      case Success(Left(ve)) =>
        log.debug(s"Could not append block ${b.uniqueId}: $ve")
        // no need to push anything downstream in here, because channels are pinned only when handling extensions
      case Failure(t) => rethrow(s"Error appending block ${b.uniqueId}", t)
    }
  }
}
