package v1.game

import javax.inject.Inject

import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import models._
import auth._
import utils.IdParser.parseLongId

import scala.concurrent.{ExecutionContext, Future}
case class GameFormInput(name: String, code: String, variant: String, description: String)

class GameController @Inject()(cc: ControllerComponents, auther: Auther)(implicit ec: ExecutionContext)
    extends AbstractController(cc) with play.api.i18n.I18nSupport{
  implicit val parser = parse.default

  private val logger = Logger(getClass)

  private val form = Form(
      mapping(
        "name" -> nonEmptyText,
        "code" -> nonEmptyText,
        "variant" -> nonEmptyText,
        "description" -> nonEmptyText
      )(GameFormInput.apply)(GameFormInput.unapply)
    )

  def list = Action.async { implicit request =>
    //logger.trace("index: ")
      Future(Ok(Json.toJson("from(AppDB.gameTable)(select(_)).toList")))
  }

  def process = (new AuthAction() andThen auther.AdminCheckAction).async { implicit request =>
    logger.trace("process: ")
    processJsonGame()
  }

  def show(id: String) = Action.async { implicit request =>
    Future(
      (for {
        gameId <- parseLongId(id, "Game")
        out = Ok(Json.toJson("AppDB.gameTable.lookup(gameId)"))
      } yield out).fold(identity, identity)
    )
  }

  private def processJsonGame[A]()(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[GameFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: GameFormInput) = {
      // TODO insert
      Future(Created(Json.toJson(
        "AppDB.gameTable.insert(new Game(input.name, input.code, input.variant, input.description))"
      )))
    }

    form.bindFromRequest().fold(failure, success)
  }
}
