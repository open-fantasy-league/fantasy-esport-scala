package v1.user

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class UserRouter @Inject()(controller: UserController) extends SimpleRouter {
  val prefix = "/v1/users"
  // TODO have a 'master router' for things like versioning query params
    def link(id: Int): String = {
      import com.netaporter.uri.dsl._
      val url = prefix / id.toString
      url.toString()
    }

  override def routes: Routes = {

    case POST(p"/") =>
      controller.add

    case POST(p"/$id") =>
      controller.update(id)

    case GET(p"/$id") =>
      controller.show(id)

    case GET(p"/$id/leagues") =>
      controller.showAllLeagueUserReq(id)

    case GET(p"/$id/leagues/$leagueId") =>
      controller.showLeagueUserReq(id, leagueId)

    case PUT(p"/$userId/join/$leagueId") =>
      controller.joinLeague(userId, leagueId)
  }

}


