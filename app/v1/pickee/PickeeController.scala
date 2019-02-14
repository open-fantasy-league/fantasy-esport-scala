package v1.pickee

import javax.inject.Inject

import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import models.AppDB._
import utils.TryHelper.tryOrResponse
import auth.{LeagueAction, AuthAction, Auther}

class PickeeController @Inject()(cc: ControllerComponents, pickeeRepo: PickeeRepo, Auther: Auther)(implicit ec: ExecutionContext) extends AbstractController(cc) with play.api.i18n.I18nSupport{

  implicit val parser = parse.default
  def getReq(leagueId: String) = (new LeagueAction( leagueId)).async { implicit request =>
    Future(inTransaction(Ok(Json.toJson(pickeeRepo.getPickeesWithFactions(request.league.id)))))
  }

  def getStatsReq(leagueId: String) = (new LeagueAction( leagueId)).async { implicit request =>
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
          mapping("id" -> of(longFormat), "cost" -> bigDecimal(10, 1))
          (RepricePickeeFormInput.apply)(RepricePickeeFormInput.unapply)
        )
      )(RepricePickeeFormInputList.apply)(RepricePickeeFormInputList.unapply)
    )
  }

  private val newPickeeForm: Form[PickeeFormInput] = {
      Form(
        mapping(
          "id" -> of(longFormat),
          "name" -> nonEmptyText,
          "value" -> bigDecimal(10, 1),
          "active" -> default(boolean, true),
          "factions" -> list(nonEmptyText)
          )(PickeeFormInput.apply)(PickeeFormInput.unapply)
      )
  }

  def recalibratePickees(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.           PermissionCheckAction).async { implicit request =>

    def failure(badForm: Form[RepricePickeeFormInputList]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(inputs: RepricePickeeFormInputList) = {
      Future {
        inTransaction {
            val leaguePickees = pickeeRepo.getPickees(request.league.id)
            val pickees: Map[Long, RepricePickeeFormInput] = inputs.pickees.map(p => p.id -> p).toMap
            pickeeTable.update(leaguePickees.filter(p => pickees.contains(p.id)).map(p => {
              p.cost = pickees.get(p.id).get.cost
              p
            }))
            // TODO print out pickees that changed
            Ok("Successfully updated pickee costs")
        }
      }
    }
    repriceForm.bindFromRequest().fold(failure, success)
  }

  def addPickee(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.           PermissionCheckAction).async { implicit request =>

    def failure(badForm: Form[PickeeFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: PickeeFormInput) = {
      Future {
        inTransaction {
            // TODO print out pickees that changed
            val newPickee = pickeeRepo.insertPickee(request.league.id, input)
            Created(Json.toJson(newPickee))
        }
      }
    }
    newPickeeForm.bindFromRequest().fold(failure, success)
  }
}
