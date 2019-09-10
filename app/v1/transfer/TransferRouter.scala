package v1.transfer

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class TransferRouter @Inject()(controller: TransferController) extends SimpleRouter {
  val prefix = "/v1/transfers"

  override def routes: Routes = {

    case POST(p"/leagues/$leagueId/users/$userId") =>
      controller.transferReq(userId, leagueId)

    case POST(p"/leagues/$leagueId/users/$userId/newPack") =>
      controller.generateCardPackReq(userId, leagueId)

    case POST(p"/leagues/$leagueId/users/$userId/appendDraft/$pickeeId") =>
      controller.appendDraftQueueReq(userId, leagueId, pickeeId)

    case GET(p"/leagues/$leagueId/users/$userId") =>
      controller.getUserTransfersReq(userId, leagueId)

    case POST(p"/leagues/$leagueId/users/$userId/recycleCards") =>
      controller.recycleCardsReq(userId, leagueId)
  }

}
