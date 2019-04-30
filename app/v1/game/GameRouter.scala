package v1.game

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._

class GameRouter @Inject()(controller: GameController) extends SimpleRouter {
  val prefix = "/v1/games"

  override def routes: Routes = {
    case GET(p"/") =>
      controller.list

    case POST(p"/") =>
      controller.process

    case GET(p"/$id") =>
      controller.show(id)
  }

}
