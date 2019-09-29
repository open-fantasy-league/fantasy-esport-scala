package v1.league

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class LeagueRouter @Inject()(controller: LeagueController) extends SimpleRouter {
  val prefix = "/v1/leagues"

  override def routes: Routes = {

    case POST(p"") =>
      controller.add

    case PATCH(p"/$id") =>
      controller.update(id)

    case POST(p"/$id") =>
      controller.update(id)

    case GET(p"/$id") =>
      controller.getWithRelatedReq(id)

    case GET(p"/$id/users") =>
      controller.getAllUsersReq(id)

    case GET(p"/$leagueId/users/$userId") =>
      controller.showUserReq(userId, leagueId)

    case GET(p"/$id/rankings/$statField") =>
      controller.getRankingsReq(id, statField)

    case POST(p"/$id/endPeriod") =>
      controller.endPeriodReq(id)

    case POST(p"/$id/startPeriod") =>
      controller.startPeriodReq(id)

    case POST(p"/$id/periods/$periodValue") =>
      controller.updatePeriodReq(id, periodValue)

    case POST(p"/$leagueId/setDraftOrder") =>
      controller.setDraftOrderReq(leagueId)

//    case GET(p"/$id/getHistoricTeams/$period") =>
//      controller.getHistoricTeamsReq(id, period)
  }

}
