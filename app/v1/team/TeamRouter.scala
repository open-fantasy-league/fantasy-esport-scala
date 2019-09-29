package v1.team

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class TeamRouter @Inject()(controller: TeamController) extends SimpleRouter {
  val prefix = "/v1/teams"

  override def routes: Routes = {

    case GET(p"/league/$leagueId/user/$userId") =>
      controller.getSingleTeamReq(leagueId, userId)

    case GET(p"/league/$leagueId/user/$userId/cards") =>
      controller.getCardsReq(leagueId, userId)

    case GET(p"/league/$leagueId") =>
      controller.getAllTeamsReq(leagueId)

    case GET(p"/league/$leagueId/cards") =>
      controller.getAllCardsReq(leagueId)
  }

}
