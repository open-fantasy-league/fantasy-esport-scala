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
import utils.IdParser.parseIntId
import v1.leagueuser.LeagueUserRepo
import v1.league.LeagueRepo

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

  def joinLeague(userId: String, leagueId: String) = Action { implicit request =>

        inTransaction {
          // TODO check not already joined
          (for {
            userId <- parseIntId(userId, "User")
            leagueId <- parseIntId(leagueId, "League")
            user <- AppDB.userTable.lookup(userId.toInt).toRight(BadRequest("User does not exist"))
            league <- AppDB.leagueTable.lookup(leagueId.toInt).toRight(BadRequest("League does not exist"))
            //todo tis hacky
            validateUnique <- if (leagueUserRepo.userInLeague(userId, leagueId)) Left(BadRequest("User already in this league")) else Right(true)
            added <- Try(leagueUserRepo.joinUsers(List(user), league, league.statFields, league.periods)).toOption.toRight(InternalServerError("Internal server error adding user to league"))
            success = "Successfully added user to league"
          } yield success).fold(identity, Ok(_))
        }
  }

  def show(userId: String) = Action { implicit request =>
    inTransaction {
      (for{
        userId <- parseIntId(userId, "User")
        user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
        success = Created(Json.toJson(user))
      } yield success).fold(identity, identity)
    }
  }

  def showLeagueUserReq(userId: String, leagueId: String) = Action { implicit request =>
    inTransaction {
      (for{
        userId <- parseIntId(userId, "User")
        leagueId <- parseIntId(leagueId, "League")
        league <- AppDB.leagueTable.lookup(leagueId.toInt).toRight(BadRequest("League does not exist"))
        user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
        leagueUser <- Try(leagueUserRepo.getLeagueUser(leagueId, userId)).toOption.toRight(
          BadRequest(s"User: {userId} not in league: {leagueId}"))
        success = Ok(Json.toJson(leagueUser))
      } yield success).fold(identity, identity)
    }
  }

  def showAllLeagueUserReq(userId: String) = Action { implicit request =>
    inTransaction {
      (for{
        userId <- parseIntId(userId, "User")
        user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
        leagueUsers = leagueUserRepo.getAllLeaguesForUser(userId)
        success = Ok(Json.toJson(leagueUsers))
      } yield success).fold(identity, identity)
    }
  }
  // TODO tolerantJson?
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
            userId <- parseIntId(userId, "User")
            user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
            updateUser <- Try(AppDB.userTable.update(user)).toOption.toRight(InternalServerError("Could not update user"))
            finished = Ok("User updated")
          } yield finished).fold(identity, identity)
        }
      }
    }

    updateForm.bindFromRequest().fold(failure, success)
  }

  def getCurrentTeamReq(leagueId: String, userId: String) = Action.async { implicit request =>
    Future {
      inTransaction {
        (for {
          leagueId <- parseIntId(leagueId, "league")
          league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league id"))
          userId <- parseIntId(userId, "User")
          user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
          out = Ok(Json.toJson(leagueUserRepo.getCurrentTeam(leagueId, userId)))
        } yield out).fold(identity, identity)
      }
    }
  }
}
