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
import auth.{LeagueAction, AuthAction, Auther}

class PickeeController @Inject()(cc: ControllerComponents, pickeeRepo: PickeeRepo, Auther: Auther, AuthAction: AuthAction)(implicit ec: ExecutionContext) extends AbstractController(cc) with play.api.i18n.I18nSupport{

  def getReq(leagueId: String) = (new LeagueAction(parse.default, leagueId)).async { implicit request =>
    Future(inTransaction(Ok(Json.toJson(pickeeRepo.getPickeesWithFactions(request.league.id)))))
  }

  def getStatsReq(leagueId: String) = (new LeagueAction(parse.default, leagueId)).async { implicit request =>
    Future{
      inTransaction {
        (for {
          period <- tryOrResponse(() => request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          out = Ok(Json.toJson(pickeeRepo.getPickeeStats(request.league.id, period)))
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

  def recalibratePickees(leagueId: String) = (AuthAction andThen Auther.AuthLeagueAction(leagueId) andThen Auther.           PermissionCheckAction).async { implicit request =>

    def failure(badForm: Form[RepricePickeeFormInputList]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(inputs: RepricePickeeFormInputList) = {
      Future {
        inTransaction {
            val leaguePickees = pickeeRepo.getPickees(request.league.id)
            val pickees: Map[Long, RepricePickeeFormInput] = inputs.pickees.map(p => p.id -> p).toMap
            pickeeTable.update(leaguePickees.filter(p => pickees.contains(p.id)).map(p => {
              p.cost = unconvertCost(pickees.get(p.id).get.cost)
              p
            }))
            // TODO print out pickees that changed
            Ok("Successfully updated pickee costs")
        }
      }
    }
    repriceForm.bindFromRequest().fold(failure, success)
  }
}
