package v1.result

import java.sql.Timestamp
import javax.inject.Inject

import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import scala.util.Try
import models.{AppDB, League, Matchu, Resultu, Points}
import utils.IdParser.parseIntId

case class ResultFormInput(
                            matchId: Long, tournamentId: Int, teamOne: String, teamTwo: String, teamOneVictory: Boolean,
                            startTstamp: Timestamp, pickees: List[PickeeFormInput]
                          )

case class PickeeFormInput(identifier: Int, isTeamOne: Boolean, stats: List[StatsFormInput])

case class StatsFormInput(field: String, value: Double)

class ResultController @Inject()(cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[ResultFormInput] = {

    Form(
      mapping(
        "matchId" -> of(longFormat),
        "tournamentId" -> number,
        "teamOne" -> nonEmptyText,
        "teamTwo" -> nonEmptyText,
        "teamOneVictory" -> boolean,
        "startTstamp" -> sqlTimestamp("yyyy-MM-dd HH:mm:ss.S"),
        "pickees" -> list(mapping(
          "identifier" -> number,
          "isTeamOne" -> boolean,
          "stats" -> list(mapping(
            "field" -> nonEmptyText,
            "value" -> of(doubleFormat)
          )(StatsFormInput.apply)(StatsFormInput.unapply))
        )(PickeeFormInput.apply)(PickeeFormInput.unapply))
      )(ResultFormInput.apply)(ResultFormInput.unapply)
    )
  }

//  def joinLeague(userId: String, leagueId: String) = Action { implicit request =>
//
//    inTransaction {
//      // TODO check not already joined
//      (for {
//        userId <- parseIntId(userId, "User")
//        leagueId <- parseIntId(leagueId, "League")
//        user <- AppDB.userTable.lookup(userId.toInt).toRight(BadRequest("User does not exist"))
//        league <- AppDB.leagueTable.lookup(leagueId.toInt).toRight(BadRequest("League does not exist"))
//        added <- Try(league.users.associate(user)).toOption.toRight(InternalServerError("Internal server error adding user to league"))
//        success = "Successfully added user to league"
//      } yield success).fold(identity, Created(_))
//    }
//  }

//  def show(userId: String) = Action { implicit request =>
//    inTransaction {
//      (for{
//        userId <- parseIntId(userId, "User")
//        user <- AppDB.userTable.lookup(userId).toRight(BadRequest("User does not exist"))
//        success = Created(Json.toJson(user))
//      } yield success).fold(identity, identity)
//    }
//  }

  def add(leagueId: String) = Action.async(parse.json){ implicit request =>
    processJsonResult(leagueId)
    //    scala.concurrent.Future{ Ok(views.html.index())}
  }

  private def processJsonResult[A](leagueId: String)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[ResultFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: ResultFormInput) = {
      println("yay")
      Future{
        inTransaction {
          (for {
            leagueId <- parseIntId(leagueId, "League")
            league <- AppDB.leagueTable.lookup(leagueId.toInt).toRight(BadRequest("League does not exist"))
            insertedMatch <- newMatch(input, league)
            insertedResults <- newResults(input, league, insertedMatch)
            insertedStats <- newStats(input, league, insertedResults)
            success = "Successfully added results"
          } yield success).fold(identity, Created(_))
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

  private def newMatch(input: ResultFormInput, league: League): Either[Result, Matchu] = {
    // TODO log/get original stack trace
    Try(AppDB.matchTable.insert(new Matchu(
      league.id, input.matchId, league.currentDay, input.tournamentId, input.teamOne, input.teamTwo,
      input.teamOneVictory
    ))).toOption.toRight(InternalServerError("Internal server error adding match"))
  }

  private def newResults(input: ResultFormInput, league: League, matchu: Matchu): Either[Result, List[Resultu]] = {
    // TODO log/get original stack trace
    val newRes = input.pickees.map(ip => new Resultu(
      matchu.id, AppDB.pickeeTable.where(p => p.leagueId === league.id and p.identifier === ip.identifier).single.id,
      input.startTstamp, new Timestamp(System.currentTimeMillis()), ip.isTeamOne
    ))
    Try({AppDB.resultTable.insert(newRes); newRes}).toOption.toRight(InternalServerError("Internal server error adding result"))
  }

  private def newStats(input: ResultFormInput, league: League, results: List[Resultu]): Either[Result, Unit] = {
    // TODO log/get original stack trace
    //               val resultId: Long,
    //val pointsFieldId: Long,
    //var value: Double
    val newStats = input.pickees.zipWithIndex.flatMap({case (ip, ind) => ip.stats.map(s => {
      val points = if (s.field == "points") s.value * league.pointsMultiplier else s.value
      new Points(results(ind).id, AppDB.leagueStatFieldsTable.where(pf => pf.leagueId === league.id and pf.name === s.field).single.id, points)
    })})
    Try(AppDB.pointsTable.insert(newStats)).toOption.toRight(InternalServerError("Internal server error adding result"))
//    Try(AppDB.pointsTable.insert(input.pickees.map(ip => new Resultu(
//      matchu.id, AppDB.pickeeTable.where(p => p.leagueId === league.id and p.identifier === ip.identifier).single.id,
//      input.startTstamp, new Timestamp(System.currentTimeMillis()), ip.isTeamOne
//    )))).toRight(InternalServerError("Internal server error adding result"))
  }
}
