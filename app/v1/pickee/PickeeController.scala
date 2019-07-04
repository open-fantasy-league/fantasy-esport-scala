package v1.pickee

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import play.api.db.Database
import utils.TryHelper.tryOrResponse
import utils.NameValueInput
import auth.{LeagueAction, AuthAction, Auther}
import v1.league.LeagueRepo

class PickeeController @Inject()(cc: ControllerComponents, pickeeRepo: PickeeRepo, Auther: Auther)
                                (implicit ec: ExecutionContext, db: Database, leagueRepo: LeagueRepo) extends AbstractController(cc) with play.api.i18n.I18nSupport{

  implicit val parser = parse.default
  def getReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future(db.withConnection { implicit c => Ok(Json.toJson(pickeeRepo.getPickeesLimits(request.league.leagueId)))})
  }

  def getStatsReq(leagueId: String) = (new LeagueAction( leagueId)).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        (for {
          period <- tryOrResponse(request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          out = Ok(Json.toJson(pickeeRepo.getPickeeStat(request.league.leagueId, Option.empty[Long], period)))
        } yield out).fold(identity, identity)
      }
    }
  }

  private val repriceForm: Form[RepricePickeeFormInputList] = {

    Form(
      mapping(
        "isInternalId" -> default(boolean, false),
        "pickees" -> list(
          mapping("id" -> of(longFormat), "price" -> bigDecimal(10, 1))
          (RepricePickeeFormInput.apply)(RepricePickeeFormInput.unapply)
        )
      )(RepricePickeeFormInputList.apply)(RepricePickeeFormInputList.unapply)
    )
  }

  private val updateForm: Form[List[UpdatePickeeFormInput]] = {
    Form(
      list(
          mapping(
            "id" -> of(longFormat), "active" -> optional(boolean),
            "limitTypes" -> list(mapping(
              "name" -> nonEmptyText,
              "value" -> nonEmptyText
            )(utils.NameValueInput.apply)(utils.NameValueInput.unapply))
          )
          (UpdatePickeeFormInput.apply)(UpdatePickeeFormInput.unapply)
        )
    )
  }

  private val newPickeeForm: Form[List[PickeeFormInput]] = {
      Form(
        list(
        mapping(
          "id" -> of(longFormat),
          "name" -> nonEmptyText,
          "value" -> bigDecimal(10, 1),
          "active" -> default(boolean, true),
          "limits" -> list(nonEmptyText)
          )(PickeeFormInput.apply)(PickeeFormInput.unapply)
        )
      )
  }

  def recalibratePickees(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.           PermissionCheckAction).async { implicit request =>

    def failure(badForm: Form[RepricePickeeFormInputList]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(inputs: RepricePickeeFormInputList) = {
      Future {
        db.withConnection { implicit c =>
            val leaguePickees = pickeeRepo.getPickees(request.league.leagueId).toList
            val pickees: Map[Long, RepricePickeeFormInput] = inputs.pickees.map(p => p.id -> p).toMap
            leaguePickees.withFilter(p => pickees.contains(p.externalPickeeId)).map(p => {
              pickeeRepo.updatePrice(request.league.leagueId, p.externalPickeeId, pickees(p.externalPickeeId).price)
            })
            // TODO print out pickees that changed
            Ok("Successfully updated pickee prices")
        }
      }
    }
    repriceForm.bindFromRequest().fold(failure, success)
  }

  def updatePickees(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.           PermissionCheckAction).async { implicit request =>

    def failure(badForm: Form[List[UpdatePickeeFormInput]]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(inputs: List[UpdatePickeeFormInput]) = {
      Future {
        val leagueId = request.league.leagueId
        db.withConnection { implicit c =>
          inputs.withFilter(_.active.isDefined).foreach(x => pickeeRepo.updateInactive(leagueId, x.id, x.active.get))
          inputs.withFilter(_.limitTypes.nonEmpty).foreach(x => pickeeRepo.updateLimits(leagueId, x.id, x.limitTypes))
//          val pickees: Map[Long, RepricePickeeFormInput] = inputs.pickees.map(p => p.id -> p).toMap
//          leaguePickees.withFilter(p => pickees.contains(p.externalPickeeId)).map(p => {
//            pickeeRepo.updatePrice(leagueId, p.externalPickeeId, pickees(p.externalPickeeId).price)
//          })
          // TODO print out pickees that changed
          Ok("Successfully updated pickees")
        }
      }
    }
    updateForm.bindFromRequest().fold(failure, success)
  }

  def addPickees(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.           PermissionCheckAction).async { implicit request =>

    def failure(badForm: Form[List[PickeeFormInput]]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(inputs: List[PickeeFormInput]) = {
      Future {
        db.withConnection { implicit c =>
            // TODO print out pickees that changed
          Created(Json.toJson(inputs.map(input => {
            val newPickeeId = pickeeRepo.insertPickee(request.league.leagueId, input)
            pickeeRepo.addLimitToPickee(request.league.leagueId, newPickeeId, input.limits)
            newPickeeId
          })))
        }
      }
    }
    newPickeeForm.bindFromRequest().fold(failure, success)
  }
}
