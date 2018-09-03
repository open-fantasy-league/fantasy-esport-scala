package v1.user

import javax.inject.Inject
import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import models.{AppDB, User}
import utils.IdParser.parseIntId
import com.github.t3hnar.bcrypt._

case class UserFormInput(name: String, password: String, email: Option[String], contactable: Boolean)

case class UpdateUserFormInput(name: Option[String], email: Option[String], contactable: Option[Boolean])

class UserController @Inject()(cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[UserFormInput] = {

    Form(
      mapping(
        "name" -> nonEmptyText,
        "password" -> nonEmptyText,
        "email" -> optional(nonEmptyText),
        "contactable" -> boolean
      )(UserFormInput.apply)(UserFormInput.unapply)
    )
  }

  private val updateForm: Form[UpdateUserFormInput] = {

    Form(
      mapping(
        "name" -> optional(nonEmptyText),
        "email" -> optional(nonEmptyText),
        "contactable" -> optional(boolean)
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
            added <- Try(league.users.associate(user)).toOption.toRight(InternalServerError("Internal server error adding user to league"))
            success = "Successfully added user to league"
          } yield success).fold(identity, Created(_))
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
        val newUser = AppDB.userTable.insert(new User(input.name, input.password.bcrypt, input.email, input.contactable))
//        for pickee in pickees{
//          val newPickee = AppDB.pickeeTable.insert(new Pickee)
//          for day in blah{
//            PickeeStats
//          }
//        }
        Future {Created(Json.toJson(newUser)) }
        //Future{Ok(views.html.index())}
      }
      //scala.concurrent.Future{ Ok(views.html.index())}
//      postResourceHandler.create(input).map { post =>
//      Created(Json.toJson(post)).withHeaders(LOCATION -> post.link)
//      }
      // TODO good practice post-redirect-get
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
      //scala.concurrent.Future{ Ok(views.html.index())}
      //      postResourceHandler.create(input).map { post =>
      //      Created(Json.toJson(post)).withHeaders(LOCATION -> post.link)
      //      }
      // TODO good practice post-redirect-get
    }

    updateForm.bindFromRequest().fold(failure, success)
  }
}
