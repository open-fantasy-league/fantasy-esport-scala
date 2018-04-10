package v1.league

import java.sql.Timestamp

import javax.inject.Inject
import org.squeryl.PrimitiveTypeMode._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import models.{AppDB, League}

case class LeagueFormInput(name: String, gameId: Int, isPrivate: Boolean, tournamentId: Int, totalDays: Int,
                           dayStart: Long, dayEnd: Long)

case class UpdateLeagueFormInput(name: Option[String], isPrivate: Option[Boolean],
                                 tournamentId: Option[Int], totalDays: Option[Int],
                           dayStart: Option[Long], dayEnd: Option[Long])


class LeagueController @Inject()(cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[LeagueFormInput] = {

    Form(
      mapping(
        "name" -> nonEmptyText,
        "gameId" -> number,
        "isPrivate" -> boolean,
        "tournamentId" -> number,
        "totalDays" -> number(min=1, max=100),
        "dayStart" -> longNumber,
        "dayEnd" -> longNumber
        "teamSize" -> default(number(min=1, max=20), 5),
        "reserveSize" -> default(number(min=0, max=20), 0),
        "captain" -> default(boolean, false)
        "transferLimit" -> default(number, -1), // use -1 for no transfer limit I think
        "startingMoney" -> default(number, 50)
        "changeDelay" -> default(number, 0), // change is generic for swap or transfer
        "factionLimit" -> default(number, -1)
        "prizeDescription" -> optional(nonEmptyText),
        "prizeEmail" -> optional(nonEmptyText)
      )(LeagueFormInput.apply)(LeagueFormInput.unapply)
    )
  }

  private val updateForm: Form[UpdateLeagueFormInput] = {

    Form(
      mapping(
        "name" -> optional(nonEmptyText),
        "isPrivate" -> optional(boolean),
        "tournamentId" -> optional(number),
        "totalDays" -> optional(number(min=1, max=100)),
        "dayStart" -> optional(longNumber),
        "dayEnd" -> optional(longNumber),
        "transferOpen" -> optional(boolean),
        "swapOpen" -> optional(boolean)
      )(UpdateLeagueFormInput.apply)(UpdateLeagueFormInput.unapply)
    )
  }

  def index = Action { implicit request =>

    Ok(views.html.index())
  }

  def show(leagueId: String) = Action { implicit request =>
    inTransaction {
      // TODO handle invalid Id
      val leagueQuery = AppDB.leagueTable.lookup(Integer.parseInt(leagueId))
      leagueQuery match{
        case Some(league) => Created(Json.toJson(league))
        case None => Ok("Yer dun fucked up")
      }
    }
  }

  def update(leagueId: String) = Action.async(BodyParsers.parse.json) { implicit request =>
    processJsonUpdateLeague(leagueId)
  }

  def add = Action.async(BodyParsers.parse.json){ implicit request =>
    processJsonLeague()
//    scala.concurrent.Future{ Ok(views.html.index())}
  }

  private def processJsonLeague[A]()(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[LeagueFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: LeagueFormInput) = {
      println("yay")
      inTransaction {
        val newLeague = AppDB.leagueTable.insert(new League(input.name, input.gameId, input.isPrivate, input.tournamentId,
          input.totalDays, new Timestamp(input.dayStart), new Timestamp(input.dayEnd)))
        Future {Created(Json.toJson(newLeague)) }
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

  private def processJsonUpdateLeague[A](leagueId: String)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[UpdateLeagueFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: UpdateLeagueFormInput) = {
      println("yay")

      val updateLeague = (league: League, input: UpdateLeagueFormInput) => {
        league.name = input.name.getOrElse(league.name)
        input.isPrivate = input.isPrivate.getOrElse(league.isPrivate)
        // etc for other fields
        AppDB.leagueTable.update(league)
        Ok("Itwerked")
        //Future { Ok("Itwerked") }
      }
      Future {
        inTransaction {
          // TODO handle invalid Id
          val leagueQuery = AppDB.leagueTable.lookup(Integer.parseInt(leagueId))
          leagueQuery match {
            case Some(league) => updateLeague(league, input)
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
