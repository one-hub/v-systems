package vsys.api.http

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import vsys.api.http.swagger.SwaggerDocService
import vsys.settings.RestAPISettings

case class CompositeHttpService(system: ActorSystem, apiTypes: Set[Class[_]], routes: Seq[ApiRoute], settings: RestAPISettings) {

  val swaggerService = new SwaggerDocService(system, ActorMaterializer()(system), apiTypes, settings)

  def withCors: Directive0 = if (settings.cors)
    respondWithHeader(`Access-Control-Allow-Origin`.*) else pass

  val compositeRoute =
    withCors(routes.map(_.route).reduce(_ ~ _)) ~
    swaggerService.routes ~
    (pathEndOrSingleSlash | path("swagger")) {
      redirect("/api-docs/index.html", StatusCodes.PermanentRedirect)
    } ~
    pathPrefix("api-docs") {
      pathEndOrSingleSlash {
        redirect("/api-docs/index.html", StatusCodes.PermanentRedirect)
      } ~
      getFromResourceDirectory("swagger-ui")
    } ~ options {
      respondWithDefaultHeaders(
        `Access-Control-Allow-Credentials`(true),
        `Access-Control-Allow-Headers`("Authorization", "Content-Type", "X-Requested-With", "Timestamp", "Signature"),
        `Access-Control-Allow-Methods`(OPTIONS, POST, PUT, GET, DELETE))(withCors(complete(StatusCodes.OK)))
    } ~ complete(StatusCodes.NotFound)
}
