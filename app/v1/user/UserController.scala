package v1.user

import java.sql.Timestamp

import javax.inject.Inject
import org.squeryl.PrimitiveTypeMode._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import models.{AppDB, User}

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

  def index = Action { implicit request =>

    Ok(views.html.index())
  }

  def show(userId: String) = Action { implicit request =>
    inTransaction {
      // TODO handle invalid Id
      val userQuery = AppDB.userTable.lookup(Integer.parseInt(userId))
      userQuery match{
        case Some(user) => Created(Json.toJson(user))
        case None => Ok("Yer dun fucked up")
      }
    }
  }

  def update(userId: String) = Action.async(BodyParsers.parse.json) { implicit request =>
    processJsonUpdateUser(userId)
  }

  def add = Action.async(BodyParsers.parse.json){ implicit request =>
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
        val newUser = AppDB.userTable.insert(new User(input.name, input.password, input.email, input.contactable))
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

      val updateUser = (user: User, input: UpdateUserFormInput) => {
        // etc for other fields
        AppDB.userTable.update(user)
        Ok("Itwerked")
        //Future { Ok("Itwerked") }
      }
      Future {
        inTransaction {
          // TODO handle invalid Id
          val userQuery = AppDB.userTable.lookup(Integer.parseInt(userId))
          userQuery match {
            case Some(user) => updateUser(user, input)
            case None => Ok("Yer dun fucked up")
          }
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
