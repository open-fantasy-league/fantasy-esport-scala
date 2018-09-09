package v1.league

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class LeagueRouter @Inject()(controller: LeagueController) extends SimpleRouter {
  val prefix = "/v1/leagues"
  // TODO have a 'master router' for things like versioning query params
//  def link(id: Int): String = {
//    import com.netaporter.uri.dsl._
//    val url = prefix / id.toString
//    url.toString()
//  }

  override def routes: Routes = {

    case PUT(p"/") =>
      controller.add

    case POST(p"/$id") =>
      controller.update(id)

    case GET(p"/$id") =>
      controller.show(id)
  }

}
