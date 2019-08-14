package v1.user

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.db._
import play.api.data.format.Formats._
import utils.IdParser.parseLongId
import v1.league.LeagueRepo
import utils.TryHelper.tryOrResponse
import auth._

case class UserFormInput(username: String, userId: Long)

case class UpdateUserFormInput(username: Option[String], externalUserId: Option[Long])

class UserController @Inject()(cc: ControllerComponents, userRepo: UserRepo, auther: Auther)
                              (implicit ec: ExecutionContext, db: Database, leagueRepo: LeagueRepo) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[UserFormInput] = {

    Form(
      mapping(
        "username" -> nonEmptyText,
        "userId" -> of(longFormat)
      )(UserFormInput.apply)(UserFormInput.unapply)
    )
  }

  private val updateForm: Form[UpdateUserFormInput] = {

    Form(
      mapping(
        "username" -> optional(nonEmptyText),
        "userId" -> optional(of(longFormat))
      )(UpdateUserFormInput.apply)(UpdateUserFormInput.unapply)
    )
  }
  implicit val parser = parse.default

  def joinLeague(userId: String, leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future{
      db.withConnection { implicit c =>
        (for {
          externalUserId <- parseLongId(userId, "User")
          username <- request.getQueryString("username").toRight(BadRequest("Must specify username query parameter"))
          _ <- if (userRepo.userInLeague(externalUserId, request.league.leagueId)) Left(BadRequest("User already in this league")) else Right(true)
          _ <- tryOrResponse(
            userRepo.joinUser(externalUserId, username, request.league),
            InternalServerError("Internal server error adding user to league"))
          success = "Successfully added user to league"
        } yield success).fold(identity, Ok(_))
      }
    }
  }

  def show(leagueId: String, userId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future{
      (for{
        userId <- parseLongId(userId, "User")
        user <- db.withConnection { implicit c => userRepo.get(request.league.leagueId, userId).toRight(BadRequest("User does not exist"))}
        success = Ok(Json.toJson(user))
      } yield success).fold(identity, identity)
    }
  }

  // TODO tolerantJson?
  def update(leagueId: String, userId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async  { implicit request =>
    processJsonUpdateUser(userId)
  }

  private def processJsonUpdateUser[A](userId: String)(implicit request: LeagueRequest[A]): Future[Result] = {
    def failure(badForm: Form[UpdateUserFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: UpdateUserFormInput) = {
      Future {
        db.withConnection { implicit c =>
          (for {
            userId <- parseLongId(userId, "User")
            user <- userRepo.get(request.league.leagueId, userId).toRight(BadRequest("User does not exist"))
            updateUser <- tryOrResponse(userRepo.update(userId, input), InternalServerError("Could not update user"))
            finished = Ok("User updated")
          } yield finished).fold(identity, identity)
        }
      }
    }

    updateForm.bindFromRequest().fold(failure, success)
  }
}
