package v1.user

import javax.inject.Inject
import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.db._
import models._
import play.api.data.format.Formats._
import utils.IdParser.parseLongId
import v1.leagueuser.LeagueUserRepo
import v1.league.LeagueRepo
import auth._

case class UserFormInput(username: String, userId: Long)

case class UpdateUserFormInput(username: Option[String], externalId: Option[Long])

class UserController @Inject()(cc: ControllerComponents, leagueUserRepo: LeagueUserRepo)
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
      inTransaction {
        db.withConnection { implicit c =>
          (for {
            userId <- parseLongId(userId, "User")
            user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
            //todo tis hacky
            validateUnique <- if (leagueUserRepo.userInLeague(userId, request.league.id)) Left(BadRequest("User already in this league")) else Right(true)
            added <- Try(leagueUserRepo.joinUsers2(List(user), request.league)).toOption.toRight(InternalServerError("Internal server error adding user to league"))
            success = "Successfully added user to league"
          } yield success).fold(identity, Ok(_))
        }
      }
    }
  }

  def show(userId: String) = Action.async { implicit request =>
    Future{
      inTransaction {
        (for{
          userId <- parseLongId(userId, "User")
          user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
          success = Ok(Json.toJson(user))
        } yield success).fold(identity, identity)
      }
    }
  }

  def showAllLeagueUserReq(userId: String) = Action.async { implicit request =>
    Future{
      inTransaction {
        (for{
          userId <- parseLongId(userId, "User")
          user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
          leagueUsers = leagueUserRepo.getAllLeaguesForUser(userId)
          success = Ok(Json.toJson(leagueUsers))
        } yield success).fold(identity, identity)
      }
    }
  }
  // TODO tolerantJson?
  def update(userId: String) = Action.async(parse.json) { implicit request =>
    processJsonUpdateUser(userId)
  }

  def add = Action.async(parse.json){ implicit request =>
    processJsonUser()
  }

  private def processJsonUser[A]()(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[UserFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: UserFormInput) = {
      inTransaction {
        println(input.username)
        println(input.userId)
        val newUser = AppDB.userTable.insert(new User(input.username, input.userId))
        Future {Created(Json.toJson(newUser)) }
      }
    }

    form.bindFromRequest().fold(failure, success)
  }

  private def processJsonUpdateUser[A](userId: String)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[UpdateUserFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: UpdateUserFormInput) = {
      Future {
        inTransaction {
          (for {
            userId <- parseLongId(userId, "User")
            user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
            updateUser <- Try(AppDB.userTable.update(user)).toOption.toRight(InternalServerError("Could not update user"))
            finished = Ok("User updated")
          } yield finished).fold(identity, identity)
        }
      }
    }

    updateForm.bindFromRequest().fold(failure, success)
  }
}
