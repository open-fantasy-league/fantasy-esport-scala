//package v1.game
//
//import javax.inject.Inject
//
//import play.api.Logger
//import play.api.data.Form
//import play.api.libs.json.Json
//import play.api.mvc._
//import app.models._
//
//import scala.concurrent.{ExecutionContext, Future}
//
///**
//  * Takes HTTP requests and produces JSON.
//  */
//class GameController @Inject()(cc: GameControllerComponents, db: AppDB)(implicit ec: ExecutionContext)
//    extends GameBaseController(cc) {
//
//  private val logger = Logger(getClass)
//
//  def index: Action[AnyContent] = GameAction.async { implicit request =>
//    logger.trace("index: ")
//    gameResourceHandler.find.map { games =>
//      Ok(Json.toJson(games))
//    }
//  }
//
//  def process: Action[AnyContent] = GameAction.async { implicit request =>
//    logger.trace("process: ")
//    processJsonGame()
//  }
//
//  def show(id: String): Action[AnyContent] = GameAction.async { implicit request =>
//    logger.trace(s"show: id = $id")
//    gameResourceHandler.lookup(id).map { game =>
//      Ok(Json.toJson(game))
//    }
//  }
//
//  private def processJsonGame[A]()(implicit request: GameRequest[A]): Future[Result] = {
//    def failure(badForm: Form[GameFormInput]) = {
//      Future.successful(BadRequest(badForm.errorsAsJson))
//    }
//
//    def success(input: GameFormInput) = {
//      gameResourceHandler.create(input).map { game =>
//        Created(Json.toJson(game)).withHeaders(LOCATION -> game.link)
//      }
//    }
//
//    form.bindFromRequest().fold(failure, success)
//  }
//}
