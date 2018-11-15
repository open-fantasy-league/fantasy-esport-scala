package v1.pickee

import java.sql.Timestamp
import javax.inject.Inject

import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import scala.util.Try
import models.AppDB._
import utils.IdParser.parseIntId
import utils.TryHelper.tryOrResponse

class PickeeController @Inject()(cc: ControllerComponents, pickeeRepo: PickeeRepo)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  def getReq(leagueId: String) = Action.async { implicit request =>
    Future{
      inTransaction {
        (for {
          leagueId <- parseIntId(leagueId, "League")
          league <- leagueTable.lookup(leagueId.toInt).toRight(BadRequest("League does not exist"))
          out = Ok(Json.toJson(league.pickees.where(_ => _).toSeq))
        } yield out).fold(identity, identity)
      }
    }
  }

  def getStatsReq(leagueId: String) = Action.async { implicit request =>
    Future{
      inTransaction {
        (for {
          leagueId <- parseIntId(leagueId, "League")
          league <- leagueTable.lookup(leagueId.toInt).toRight(BadRequest("League does not exist"))
          day <- tryOrResponse(() => request.getQueryString("day").map(_.toInt), BadRequest("Invalid day format"))
          out = Ok(Json.toJson(pickeeRepo.getPickeeStats(leagueId, day)))
        } yield out).fold(identity, identity)
      }
    }
  }
}
