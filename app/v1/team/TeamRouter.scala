package v1.team

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class TeamRouter @Inject()(controller: TeamController) extends SimpleRouter {
  val prefix = "/v1/teams"

  def link(id: Long): String = {
    import com.netaporter.uri.dsl._
    val url = prefix / id.toString
    url.toString()
  }

  override def routes: Routes = {

    case GET(p"/$leagueId/$userId") =>
      controller.getSingleTeamReq(leagueId, userId)

    case GET(p"/$leagueId") =>
      controller.getAllTeamsReq(leagueId)
  }

}
