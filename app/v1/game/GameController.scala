package v1.game

import javax.inject.Inject
import java.sql.Connection

import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.db.Database
import models._
import anorm._
import auth._
import utils.IdParser.parseLongId

import scala.concurrent.{ExecutionContext, Future}
case class GameFormInput(name: String, code: String, variant: String, description: String)

class GameController @Inject()(cc: ControllerComponents, auther: Auther, db: Database)(implicit ec: ExecutionContext)
    extends AbstractController(cc) with play.api.i18n.I18nSupport{
  implicit val parser = parse.default

  private val logger = Logger("application")

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
    db.withConnection { implicit c: Connection =>
      val games = SQL("select game_id, name, code, variant, description from game;").as(GameRow.parser.*)
      Future(Ok(Json.toJson(games)))
    }
  }

  def process = (new AuthAction() andThen auther.AdminCheckAction).async { implicit request =>
    logger.trace("process: ")
    processJsonGame()
  }

  def show(id: String) = Action.async { implicit request =>
    Future(
      db.withConnection{ implicit c: Connection =>
        (for {
          gameId <- parseLongId(id, "Game")
          game <- SQL(
            s"select game_id, name, code, variant, description from game where game_id = $gameId;"
          ).as(GameRow.parser.singleOpt).toRight(BadRequest(s"Game does not exist: $gameId"))
          out = Ok(Json.toJson(game))
        } yield out).fold(identity, identity)
      }
    )
  }

  private def processJsonGame[A]()(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[GameFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: GameFormInput) = {
      Future {
        db.withConnection { implicit c: Connection =>
          val newGameId = SQL("insert into game(name, code, variant, description) VALUES ({}, {}, {}, {});").on(
            "name" -> input.name, "code" -> input.code, "variant" -> input.variant, "description" -> input.description
          ).executeInsert().get
          Created(Json.toJson(newGameId))
        }
      }
    }

    form.bindFromRequest().fold(failure, success)
  }
}
