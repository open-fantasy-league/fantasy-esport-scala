//package v1.league
//
//import javax.inject.Inject
//
//import play.api.routing.Router.Routes
//import play.api.routing.SimpleRouter
//import play.api.routing.sird._
//
//
//class transferRouter @Inject()(controller: LeagueController) extends SimpleRouter {
//  val prefix = "/v1/leagues"
//
//  def link(id: Int): String = {
//    import com.netaporter.uri.dsl._
//    val url = prefix / id.toString
//    url.toString()
//  }
//
//  override def routes: Routes = {
//    case GET(p"/") =>
//      controller.index
//
//    case PUT(p"/") =>
//      controller.add
//
//    case POST(p"/$id") =>
//      controller.update(id)
//
//    case GET(p"/$id") =>
//      controller.show(id)
//  }
//
//}
