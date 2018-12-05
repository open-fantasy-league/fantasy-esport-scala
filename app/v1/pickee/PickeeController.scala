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
import utils.CostConverter.unconvertCost
import utils.IdParser.parseLongId
import utils.TryHelper.tryOrResponse

class PickeeController @Inject()(cc: ControllerComponents, pickeeRepo: PickeeRepo)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  def getReq(leagueId: String) = Action.async { implicit request =>
    Future{
      inTransaction {
        (for {
          leagueId <- parseLongId(leagueId, "League")
          league <- leagueTable.lookup(leagueId).toRight(BadRequest("League does not exist"))
          //out = Ok(Json.toJson(league.pickees.where(_ => _).toSeq))
          out = Ok(Json.toJson(pickeeRepo.getPickeesWithFactions(leagueId)))
        } yield out).fold(identity, identity)
      }
    }
  }

  def getStatsReq(leagueId: String) = Action.async { implicit request =>
    Future{
      inTransaction {
        (for {
          leagueId <- parseLongId(leagueId, "League")
          league <- leagueTable.lookup(leagueId).toRight(BadRequest("League does not exist"))
          period <- tryOrResponse(() => request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          out = Ok(Json.toJson(pickeeRepo.getPickeeStats(leagueId, period)))
        } yield out).fold(identity, identity)
      }
    }
  }

  private val repriceForm: Form[RepricePickeeFormInputList] = {

    Form(
      mapping(
        "isInternalId" -> default(boolean, false),
        "pickees" -> list(
          mapping("id" -> of(longFormat), "cost" -> of(doubleFormat))
          (RepricePickeeFormInput.apply)(RepricePickeeFormInput.unapply)
        )
      )(RepricePickeeFormInputList.apply)(RepricePickeeFormInputList.unapply)
    )
  }

  def recalibratePickees(leagueId: String) = Action.async { implicit request =>

    def failure(badForm: Form[RepricePickeeFormInputList]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(inputs: RepricePickeeFormInputList) = {
      Future {
        inTransaction {
          (for {
            leagueId <- parseLongId(leagueId, "league")
            league <- leagueTable.lookup(leagueId).toRight(BadRequest("League does not exist"))
            leaguePickees = pickeeRepo.getPickees(leagueId)
            pickees: Map[Long, RepricePickeeFormInput] = inputs.pickees.map(p => p.id -> p).toMap
            _ = pickeeTable.update(leaguePickees.filter(p => pickees.contains(p.id)).map(p => {
              p.cost = unconvertCost(pickees.get(p.id).get.cost); p
            }))
            // TODO print out pickees that changed
            out = Ok("Successfully updated pickee costs")
          } yield out).fold(identity, identity)
        }
      }
    }
    repriceForm.bindFromRequest().fold(failure, success)
  }
}
