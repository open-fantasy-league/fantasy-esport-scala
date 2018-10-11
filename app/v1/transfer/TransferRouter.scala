package v1.transfer

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class TransferRouter @Inject()(controller: TransferController) extends SimpleRouter {
  val prefix = "/v1/transfers"

  def link(id: Int): String = {
    import com.netaporter.uri.dsl._
    val url = prefix / id.toString
    url.toString()
  }

  override def routes: Routes = {

    case POST(p"/process/$leagueId") =>
      controller.processTransfersReq(leagueId)

    case POST(p"/$leagueId/$userId") =>
      controller.scheduleTransferReq(userId, leagueId)
  }

}
