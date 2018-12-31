package v1.transfer

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class TransferRouter @Inject()(controller: TransferController) extends SimpleRouter {
  val prefix = "/v1/transfers"

  def link(id: Long): String = {
    import com.netaporter.uri.dsl._
    val url = prefix / id.toString
    url.toString()
  }

  override def routes: Routes = {
    // TODO background task continually processing
    case POST(p"/$leagueId/process") =>
      controller.processTransfersReq(leagueId)

    case POST(p"/leagues/$leagueId/users/$userId") =>
      controller.scheduleTransferReq(userId, leagueId)

    case GET(p"/leagues/$leagueId/users/$userId") =>
      controller.getUserTransfersReq(userId, leagueId)
  }

}
