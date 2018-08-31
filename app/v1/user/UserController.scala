package v1.user

import javax.inject.Inject
import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import models.{AppDB, User}
import utils.IdParser.parseIntId
import com.github.t3hnar.bcrypt._

case class UserFormInput(name: String, password: String, email: Option[String], contactable: Boolean)

case class UpdateUserFormInput(name: Option[String], email: Option[String], contactable: Option[Boolean])

case class TransferFormInput(buy: Option[List[Int]], sell: Option[List[Int]])


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

  private val transferForm: Form[TransferFormInput] = {

    Form(
      mapping(
        "buy" -> optional(list(number)),
        "sell" -> optional(list(number)),
      )(TransferFormInput.apply)(TransferFormInput.unapply)
    )
  }

  // todo add a transfer check call
  def transfer(userId: String, leagueId: String) = Action.async(parse.json) { implicit request =>
    processJsonTransfer(userId, leagueId)
  }

  def joinLeague(userId: String, leagueId: String) = Action { implicit request =>
    inTransaction {
      // check not already joined
      //
      AppDB.userTable.lookup(userId.toInt) match {
        case Some(user) => {
          AppDB.leagueTable.lookup(leagueId.toInt) match {
            case Some(league) => {
              league.users.associate(user)
              Created("Successfully added user to league")
            }
            case None => BadRequest("League does not exist")
          }
        }
        case None => BadRequest("User does not exist")
      }
    }
  }

  def show(userId: String) = Action { implicit request =>
    parseIntId(userId, (convertedId) => {
      inTransaction {
        val userQuery = AppDB.userTable.lookup(convertedId)
        userQuery match{
          case Some(user) => Created(Json.toJson(user))
          case None => Ok("Yer dun fucked up")
        }
      }
    })
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

  private def processJsonTransfer[A](userId: String, leagueId: String)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[TransferFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: TransferFormInput) = {
      println("yay")

      val updateUser = (user: User, input: TransferFormInput) => {
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

    transferForm.bindFromRequest().fold(failure, success)
  }
}
