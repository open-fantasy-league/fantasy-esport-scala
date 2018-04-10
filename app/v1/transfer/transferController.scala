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

case class TransferFormInput(leagueId: Int, userId: Int, pickeeIdentifier: Int, isBuy: Boolean)

case class UpdateLeagueFormInput(name: Option[String], isPrivate: Option[Boolean],
                                 tournamentId: Option[Int], totalDays: Option[Int],
                           dayStart: Option[Long], dayEnd: Option[Long])


class LeagueController @Inject()(cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[TranferFormInput] = {

    Form(
      mapping(
        "transfers" -> List(Sales)number,
        "userId" -> number
      )(LeagueFormInput.apply)(LeagueFormInput.unapply)
    )
  }

  def index = Action { implicit request =>

    Ok(views.html.index())
  }

  def show(id_: String) = Action { implicit request =>
    inTransaction {
      // TODO handle invalid Id
      val query = AppDB.leagueUserTable.lookup(Integer.parseInt(id_))
      query match{
        case Some(x) => Created(Json.toJson(x))
        case None => Ok("Yer dun fucked up")
      }
    }
  }

  def update(leagueId: String) = Action.async(BodyParsers.parse.json) { implicit request =>
    processJsonUpdateLeague(leagueId)
  }

  def add = Action.async(BodyParsers.parse.json){ implicit request =>
    processJsonLeagueUser()
//    scala.concurrent.Future{ Ok(views.html.index())}
  }

  private def processJsonLeagueUser[A]()(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[LeagueFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: LeagueFormInput) = {
      println("yay")
      Future {
        inTransaction {
          val league = AppDB.leagueTable.lookup(Integer.parseInt(input.leagueId))
          val newLeagueUser = AppDB.leagueUserTable.insert(new LeagueUser(
            league.id, input.userId, league.startingMoney, Timestamp(), league.transferLimit, None
          ))
          for (leagueUserStatField in blah)
            for day in seq(0, league.totalDays) // add -1
          AppDB.leagueUserStatsTable.insert(new LeagueUserStats(

          ))
          Created(Json.toJson(newLeagueUser))
          //Future{Ok(views.html.index())}
        }
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
