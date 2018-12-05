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

    case POST(p"/$id") =>
      controller.update(id)

    case GET(p"/$id") =>
      controller.getWithRelatedReq(id)

    case GET(p"/$id/users") =>
      controller.getAllUsersReq(id)

    case GET(p"/$id/rankings/$statField") =>
      controller.getRankingsReq(id, statField)

    case POST(p"/$id/endDay") =>
      controller.endDayReq(id)

    case POST(p"/$id/startDay") =>
      controller.startDayReq(id)

    case POST(p"/$id/periods/$periodValue") =>
      controller.updatePeriodReq(id, periodValue)

    case GET(p"/$id/getTeams") =>
      controller.getCurrentTeamsReq(id)

    case GET(p"/$id/getHistoricTeams/$period") =>
      controller.getHistoricTeamsReq(id, period)
  }

}
