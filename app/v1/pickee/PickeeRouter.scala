package v1.pickee

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class PickeeRouter @Inject()(controller: PickeeController) extends SimpleRouter {
  val prefix = "/v1/pickees"

  def link(id: Int): String = {
    import com.netaporter.uri.dsl._
    val url = prefix / id.toString
    url.toString()
  }

  override def routes: Routes = {

//    case POST(p"/$leagueId") =>
//      controller.add(leagueId)

    case GET(p"/$leagueId") =>
      controller.getReq(leagueId)

    case GET(p"/stats/$leagueId") =>
      controller.getStatsReq(leagueId)

//    case GET(p"/$id") =>
//      controller.show(id)
  }

}


