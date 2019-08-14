package v1.user

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class UserRouter @Inject()(controller: UserController) extends SimpleRouter {
  val prefix = "/v1/users"

  override def routes: Routes = {

    case POST(p"/$id/leagues/$leagueId") =>
      controller.update(leagueId, id)

    case GET(p"/$id/leagues/$leagueId") =>
      controller.show(leagueId, id)

    case PUT(p"/$userId/join/$leagueId") =>
      controller.joinLeague(userId, leagueId)

    case POST(p"/$userId/join/$leagueId") =>
      controller.joinLeague(userId, leagueId)
  }

}


