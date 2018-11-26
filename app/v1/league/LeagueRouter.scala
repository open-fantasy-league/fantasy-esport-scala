package v1.league

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._
import entry.SquerylEntrypointForMyApp._


class LeagueRouter @Inject()(controller: LeagueController) extends SimpleRouter {
  val prefix = "/v1/leagues"
  // TODO have a 'master router' for things like versioning query params
//  def link(id: Int): String = {
//    import com.netaporter.uri.dsl._
//    val url = prefix / id.toString
//    url.toString()
//  }

  override def routes: Routes = {

    case POST(p"/") =>
      controller.add

    case PATCH(p"/$id") =>
      controller.update(id)

    case GET(p"/$id") =>
      controller.getWithRelatedReq(id)

    case GET(p"/rankings/$statField/$id") =>
      controller.getRankingsReq(id, statField)

    case POST(p"/endDay/$id") =>
      controller.endDayReq(id)

    case POST(p"/startDay/$id") =>
      controller.startDayReq(id)

    case GET(p"/getHistoricTeams/$leagueId/$day") =>
      controller.getHistoricTeamsReq(leagueId, day)
  }

}
