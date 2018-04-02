package v1.league

import java.sql.Timestamp

import javax.inject.Inject
import org.squeryl.PrimitiveTypeMode._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._

/**
  * A very small controller that renders a home page.
  */
class LeagueController @Inject()(cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def index = Action { implicit request =>

    Ok(views.html.index())
  }

  def add = Action.async(BodyParsers.parse.json){ implicit request =>
    val name = (request.body \ "name").as[String]
    val gameId = (request.body \ "gameId").as[Int]
    val isPrivate = (request.body \ "isPrivate").as[Boolean]
    val tournamentId = (request.body \ "tournamentId").as[Int]
    val totalDays = (request.body \ "totalDays").as[Int]
    val dayStart = (request.body \ "dayStart").as[Long]  //as Timestamp
    // No Json deserializer found for type java.sql.Timestamp. Try to implement an implicit Reads or Format for this type.
    val dayEnd = (request.body \ "dayEnd").as[Long]
    println(name)
//    recipeRepo.save(BSONDocument(
//      Name -> name,
//      Description -> description,
//      Author -> author
//    )).map(result => Created)
    scala.concurrent.Future{ Ok(views.html.index())}
  }

  def show = Action { implicit request =>

    Ok(views.html.index())
  }
}
