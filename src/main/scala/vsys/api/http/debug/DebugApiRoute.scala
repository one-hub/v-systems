package vsys.api.http.debug

import java.net.{InetAddress, URI}
import java.util.concurrent.ConcurrentMap
import javax.ws.rs.Path

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import io.netty.channel.Channel
import io.netty.channel.group.ChannelGroup
import io.swagger.annotations._
import play.api.libs.json._
import scorex.crypto.encode.Base58
import vsys.account.Address
import vsys.api.http._
import vsys.blockchain.history.History
import vsys.blockchain.state.reader.StateReader
import vsys.blockchain.state.{ByteStr, LeaseInfo, Portfolio}
import vsys.blockchain.{BlockchainUpdater, UtxPool}
import vsys.network._
import vsys.settings.RestAPISettings
import vsys.utils.crypto.hash.FastCryptographicHash
import vsys.wallet.Wallet

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import DebugApiRoute._


@Path("/debug")
@Api(value = "/debug", authorizations = Array(new Authorization("api_key")))
case class DebugApiRoute(settings: RestAPISettings,
                         wallet: Wallet,
                         stateReader: StateReader,
                         history: History,
                         peerDatabase: PeerDatabase,
                         establishedConnections: ConcurrentMap[Channel, PeerInfo],
                         blockchainUpdater: BlockchainUpdater,
                         allChannels: ChannelGroup,
                         utxStorage: UtxPool) extends ApiRoute {

  override lazy val route = pathPrefix("debug") {
    blocks ~ state ~ info ~ stateVsys ~ rollback ~ rollbackTo ~ blacklist ~ portfolios
  }

  @Path("/blocks/{howMany}")
  @ApiOperation(value = "Blocks", notes = "Get sizes and full hashes for last blocks", httpMethod = "GET",
    authorizations = Array(new Authorization("api_key")))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "howMany",
      value = "How many last blocks to take",
      required = true,
      dataType = "string",
      paramType = "path")
  ))
  def blocks: Route = {
    (path("blocks" / IntNumber) & get & withAuth) { howMany =>
      complete(JsArray(history.lastBlocks(howMany).map { block =>
        val bytes = block.bytes
        Json.obj(bytes.length.toString -> Base58.encode(FastCryptographicHash(bytes)))
      }))
    }
  }

  @Path("/state")
  @ApiOperation(value = "State", notes = "Get current state", httpMethod = "GET",
    authorizations = Array(new Authorization("api_key")))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json state")))
  def state: Route = (path("state") & get & withAuth) {
    complete(stateReader.accountPortfolios
      .map { case (k, v) =>
        k.bytes.base58 -> v.balance
      }
    )
  }

  @Path("/portfolios/{address}")
  @ApiOperation(
    value = "Portfolio",
    notes = "Get current portfolio considering pessimistic transactions in the UTX pool",
    httpMethod = "GET",
    authorizations = Array(new Authorization("api_key"))
  )
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "address",
      value = "An address of portfolio",
      required = true,
      dataType = "string",
      paramType = "path"
    ),
    new ApiImplicitParam(
      name = "considerUnspent",
      value = "Taking into account pessimistic transactions from UTX pool",
      required = false,
      dataType = "boolean",
      paramType = "query",
      defaultValue = "true"
    )
  ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json portfolio")))
  def portfolios: Route = path("portfolios" / Segment) { (rawAddress) =>
    (get & withAuth & parameter('considerUnspent.as[Boolean])) { (considerUnspent) =>
      Address.fromString(rawAddress) match {
        case Left(_) => complete(InvalidAddress)
        case Right(address) =>
          val portfolio = if (considerUnspent) utxStorage.portfolio(address) else stateReader.accountPortfolio(address)
          complete(Json.toJson(portfolio))
      }
    }
  }

  @Path("/stateVsys/{height}")
  @ApiOperation(value = "State at block", notes = "Get state at specified height", httpMethod = "GET",
    authorizations = Array(new Authorization("api_key")))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "height", value = "height", required = true, dataType = "integer", paramType = "path")
  ))
  def stateVsys: Route = (path("stateVsys" / IntNumber) & get & withAuth) { height =>
    val result = stateReader.accountPortfolios.keys
      .map(acc => acc.stringRepr -> stateReader.balanceAtHeight(acc, height))
      .filter(_._2 != 0)
      .toMap
    complete(result)
  }

  private def rollbackToBlock(blockId: ByteStr, returnTransactionsToUtx: Boolean): Future[ToResponseMarshallable] = Future {
    blockchainUpdater.removeAfter(blockId) match {
      case Right(txs) =>
        allChannels.broadcast(LocalScoreChanged(history.score()))
        if (returnTransactionsToUtx) {
          txs.foreach(tx => utxStorage.putIfNew(tx))
        }
        Json.obj("BlockId" -> blockId.toString): ToResponseMarshallable
      case Left(error) => ApiError.fromValidationError(error)
    }
  }

  @Path("/rollback")
  @ApiOperation(value = "Rollback to height", notes = "Removes all blocks after given height", httpMethod = "POST",
    authorizations = Array(new Authorization("api_key")))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "vsys.api.http.debug.RollbackParams",
      defaultValue = "{\n\t\"rollbackTo\": 3,\n\t\"returnTransactionsToUTX\": false\n}"
    )
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "200 if success, 404 if there are no block at this height")
  ))
  def rollback: Route = withAuth {
    (path("rollback") & post) {
      json[RollbackParams] { params =>
        history.blockAt(params.rollbackTo) match {
          case Some(block) =>
            rollbackToBlock(block.uniqueId, params.returnTransactionsToUtx)
          case None =>
            (StatusCodes.BadRequest, "Block at height not found")
        }
      } ~ complete(StatusCodes.BadRequest)
    }
  }

  @Path("/info")
  @ApiOperation(value = "State", notes = "All info you need to debug", httpMethod = "GET")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Json state")
  ))
  def info: Route = (path("info") & get & withAuth) {
    complete(Json.obj(
      "stateHeight" -> stateReader.height,
      "stateHash" -> stateReader.accountPortfoliosHash
    ))
  }


  @Path("/rollback-to/{signature}")
  @ApiOperation(value = "Block signature", notes = "Rollback the state to the block with a given signature", httpMethod = "DELETE",
    authorizations = Array(new Authorization("api_key")))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "signature", value = "Base58-encoded block signature", required = true, dataType = "string", paramType = "path")
  ))
  def rollbackTo: Route = path("rollback-to" / Segment) { signature =>
    (delete & withAuth) {
      ByteStr.decodeBase58(signature) match {
        case Success(sig) =>
          complete(rollbackToBlock(sig, returnTransactionsToUtx = false))
        case _ =>
          complete(InvalidSignature)
      }
    }
  }

  @Path("/blacklist")
  @ApiOperation(value = "Blacklist given peer", notes = "Moving peer to blacklist", httpMethod = "POST",
    authorizations = Array(new Authorization("api_key")))
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "address", value = "IP address of node", required = true, dataType = "string", paramType = "body")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "200 if success, 404 if there are no peer with such address")
  ))
  def blacklist: Route = withAuth {
    (path("blacklist") & post) {
      entity(as[String]) { socketAddressString =>
        try {
          val uri = new URI("node://" + socketAddressString)
          val address = InetAddress.getByName(uri.getHost)
          establishedConnections.entrySet().stream().forEach(entry => {
            if (entry.getValue.remoteAddress.getAddress == address) {
              peerDatabase.blacklistAndClose(entry.getKey, "Debug API request")
            }
          })
          complete(StatusCodes.OK)
        } catch {
          case NonFatal(_) => complete(StatusCodes.BadRequest)
        }
      } ~ complete(StatusCodes.BadRequest)
    }
  }

}

object DebugApiRoute {
  implicit val assetsFormat: Format[Map[ByteStr, Long]] = Format[Map[ByteStr, Long]](
    _ match {
      case JsObject(m) => m.foldLeft[JsResult[Map[ByteStr, Long]]](JsSuccess(Map.empty)) {
        case (e: JsError, _) => e
        case (JsSuccess(m, _), (rawAssetId, JsNumber(count))) =>
          (ByteStr.decodeBase58(rawAssetId), count) match {
            case (Success(assetId), count) if count.isValidLong => JsSuccess(m.updated(assetId, count.toLong))
            case (Failure(_), _) => JsError(s"Can't parse '$rawAssetId' as base58 string")
            case (_, count) => JsError(s"Invalid count of assets: $count")
          }
        case (_, (_, rawCount)) =>
          JsError(s"Invalid count of assets: $rawCount")
      }
      case _ => JsError("The map is expected")
    },
    m => Json.toJson(m.map { case (assetId, count) => assetId.base58 -> count })
  )
  implicit val leaseInfoFormat: Format[LeaseInfo] = Json.format
  implicit val portfolioFormat: Format[Portfolio] = Json.format
}
