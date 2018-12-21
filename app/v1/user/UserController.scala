package v1.user

import javax.inject.Inject
import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import models._
import play.api.data.format.Formats._
import utils.IdParser.parseLongId
import v1.leagueuser.LeagueUserRepo
import v1.league.LeagueRepo
import auth._

case class UserFormInput(username: String, externalId: Option[Long])

case class UpdateUserFormInput(username: Option[String], externalId: Option[Long])

class UserController @Inject()(cc: ControllerComponents, leagueUserRepo: LeagueUserRepo, leagueRepo: LeagueRepo)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[UserFormInput] = {

    Form(
      mapping(
        "username" -> nonEmptyText,
        "externalId" -> optional(of(longFormat))
      )(UserFormInput.apply)(UserFormInput.unapply)
    )
  }

  private val updateForm: Form[UpdateUserFormInput] = {

    Form(
      mapping(
        "username" -> optional(nonEmptyText),
        "externalId" -> optional(of(longFormat))
      )(UpdateUserFormInput.apply)(UpdateUserFormInput.unapply)
    )
  }
  implicit val parser = parse.default

  def joinLeague(userId: String, leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future{
      inTransaction {
        // TODO check not already joined
        (for {
          userId <- parseLongId(userId, "User")
          user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
          //todo tis hacky
          validateUnique <- if (leagueUserRepo.userInLeague(userId, request.league.id)) Left(BadRequest("User already in this league")) else Right(true)
          added <- Try(leagueUserRepo.joinUsers(List(user), request.league)).toOption.toRight(InternalServerError("Internal server error adding user to league"))
          success = "Successfully added user to league"
        } yield success).fold(identity, Ok(_))
      }
    }
  }

  def show(userId: String) = Action.async { implicit request =>
    Future{
      inTransaction {
        (for{
          userId <- parseLongId(userId, "User")
          user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
          success = Created(Json.toJson(user))
        } yield success).fold(identity, identity)
      }
    }
  }

  def showLeagueUserReq(userId: String, leagueId: String) = (new LeagueAction(leagueId) andThen new LeagueUserAction(userId).apply()).async { implicit request =>
    Future(Ok(Json.toJson(request.leagueUser)))
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
  // TODO userAuth -- associate a user with apiKey that created it?
  def update(userId: String) = Action.async(parse.json) { implicit request =>
    processJsonUpdateUser(userId)
  }

  def add = Action.async(parse.json){ implicit request =>
    processJsonUser()
//    scala.concurrent.Future{ Ok(views.html.index())}
  }

  private def processJsonUser[A]()(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[UserFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: UserFormInput) = {
      println("yay")
      inTransaction {
        val newUser = AppDB.userTable.insert(new User(input.username, input.externalId))
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
      println("yay")

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

  def getCurrentTeamReq(leagueId: String, userId: String) = (new LeagueAction(leagueId) andThen new LeagueUserAction(userId).apply()).async { implicit request =>
    Future(inTransaction(Ok(Json.toJson(leagueUserRepo.getCurrentTeam(request.league.id, request.user.id)))))
  }
}
