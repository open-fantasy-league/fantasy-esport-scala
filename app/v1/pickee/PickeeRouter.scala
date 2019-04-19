package v1.pickee

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class PickeeRouter @Inject()(controller: PickeeController) extends SimpleRouter {
  val prefix = "/v1/pickees"

  override def routes: Routes = {

    case GET(p"/$leagueId") =>
      controller.getReq(leagueId)

    case GET(p"/leagues/$leagueId/stats") =>
      controller.getStatsReq(leagueId)

    case POST(p"/leagues/$leagueId/updateCosts") =>
      controller.recalibratePickees(leagueId)

    case POST(p"/leagues/$leagueId/add") =>
      controller.addPickee(leagueId)

  }

}


