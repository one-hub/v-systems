package vsys.api.http.leasing

import javax.ws.rs.Path

import akka.http.scaladsl.server.Route
import vsys.blockchain.UtxPool
import vsys.settings.RestAPISettings
import io.netty.channel.group.ChannelGroup
import io.swagger.annotations._
import vsys.api.http.BroadcastRoute
import vsys.api.http._

@Path("/leasing/broadcast")
@Api(value = "/leasing")
case class LeaseBroadcastApiRoute(
    settings: RestAPISettings,
    utx: UtxPool,
    allChannels: ChannelGroup) extends ApiRoute with BroadcastRoute {
  override val route = pathPrefix("leasing" / "broadcast") {
    signedLease ~ signedLeaseCancel
  }

  @Path("/lease")
  @ApiOperation(value = "Broadcasts a signed lease transaction",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "vsys.api.http.leasing.SignedLeaseRequest",
      defaultValue = "{\n\t\"amount\": 100000000,\n\t\"recipient\": \"3NBsppTVpai9jq6agi9wXXrWhaMPPig48Aw\",\n\t\"senderPublicKey\": \"11111\",\n\t\"fee\": 100000,\n\t\"feeScale\": 100,\n\t\"timestamp\": 12345678,\n\t\"signature\": \"asdasdasd\"\n}"
    )
  ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json with response or error")))
  def signedLease: Route = (path("lease") & post) {
    json[SignedLeaseRequest] { leaseReq =>
      doBroadcast(leaseReq.toTx)
    }
  }

  @Path("/cancel")
  @ApiOperation(value = "Broadcasts a signed lease cancel transaction",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "vsys.api.http.leasing.SignedLeaseCancelRequest",
      defaultValue = "{\n\t\"sender\": \"3Myss6gmMckKYtka3cKCM563TBJofnxvfD7\",\n\t\"txId\": \"ABMZDPY4MyQz7kKNAevw5P9eNmRErMutJoV9UNeCtqRV\",\n\t\"fee\": 10000000,\n\t\"feeScale\": 100,\n\t\"timestamp\": 12345678,\n\t\"signature\": \"asdasdasd\"\n}"
    )
  ))
  def signedLeaseCancel: Route = (path("cancel") & post) {
    json[SignedLeaseCancelRequest] { leaseCancelReq =>
      doBroadcast(leaseCancelReq.toTx)
    }
  }
}
