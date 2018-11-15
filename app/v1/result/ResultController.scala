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
import utils.TryHelper.tryOrResponse

case class ResultFormInput(
                            matchId: Long, tournamentId: Int, teamOne: String, teamTwo: String, teamOneVictory: Boolean,
                            startTstamp: Timestamp, pickees: List[PickeeFormInput]
                          )

case class PickeeFormInput(externalId: Int, isTeamOne: Boolean, stats: List[StatsFormInput])

case class InternalPickee(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

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
          "externalId" -> number,
          "isTeamOne" -> boolean,
          "stats" -> list(mapping(
            "field" -> nonEmptyText,
            "value" -> of(doubleFormat)
          )(StatsFormInput.apply)(StatsFormInput.unapply))
        )(PickeeFormInput.apply)(PickeeFormInput.unapply))
      )(ResultFormInput.apply)(ResultFormInput.unapply)
    )
  }

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
            internalPickee = convertExternalToInternalPickeeId(input.pickees, league)
            insertedMatch <- newMatch(input, league)
            insertedResults <- newResults(input, league, insertedMatch, internalPickee)
            insertedStats <- newStats(league, insertedMatch.id, internalPickee)
            updatedStats <- updateStats(insertedStats, league)
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

  private def convertExternalToInternalPickeeId(pickees: List[PickeeFormInput], league: League): List[InternalPickee] = {
    pickees.map(ip => {
      val internalId = AppDB.pickeeTable.where(p => p.leagueId === league.id and p.externalId === ip.externalId).single.id
      InternalPickee(internalId, ip.isTeamOne, ip.stats)
    })
  }

  private def newMatch(input: ResultFormInput, league: League): Either[Result, Matchu] = {
    // TODO log/get original stack trace
    tryOrResponse[Matchu](() => AppDB.matchTable.insert(new Matchu(
      league.id, input.matchId, league.currentDay, input.tournamentId, input.teamOne, input.teamTwo,
      input.teamOneVictory
    )), InternalServerError("Internal server error adding match"))
  }

  private def newResults(input: ResultFormInput, league: League, matchu: Matchu, pickees: List[InternalPickee]): Either[Result, List[Resultu]] = {
    // TODO log/get original stack trace
    val newRes = pickees.map(p => new Resultu(
      matchu.id, p.id,
      input.startTstamp, new Timestamp(System.currentTimeMillis()), p.isTeamOne
    ))
    Try({AppDB.resultTable.insert(newRes); newRes}).toOption.toRight(InternalServerError("Internal server error adding result"))
  }

  private def newStats(league: League, matchId: Long, pickees: List[InternalPickee]): Either[Result, List[(Points, Long)]] = {
    // doing add results, add points to pickee, and to league user all at once
    // (was not like this in python)
    // as learnt about postgreq MVCC which means transactions sees teams as they where when transcation started
    // i.e. avoidss what i was worried about where if user transferred a hero midway through processing, maybe they can
    // score points from hero they were selling, then also hero they were buying, with rrace condition
    // but this isnt actually an issue https://devcenter.heroku.com/articles/postgresql-concurrency
    // TODO log/get original stack trace
    // DOLIST actually creatte leagueuserstats tables
    val newStats = pickees.flatMap(ip => ip.stats.map(s => {
      val result = AppDB.resultTable.where(
        r => r.matchId === matchId and r.pickeeId === ip.id
        ).single
      println(result)
      val points = if (s.field == "points") s.value * league.pointsMultiplier else s.value
      (new Points(result.id, AppDB.leagueStatFieldTable.where(pf => pf.leagueId === league.id and pf.name === s.field).single.id, points),
        ip.id)
    }))
    // TODO try top option right func
    val out = Try({AppDB.pointsTable.insert(newStats.map(_._1)); newStats}).toOption.toRight(InternalServerError("Internal server error adding result"))
    out
//    Try(AppDB.pointsTable.insert(input.pickees.map(ip => new Resultu(
//      matchu.id, AppDB.pickeeTable.where(p => p.leagueId === league.id and p.externalId === ip.externalId).single.id,
//      input.startTstamp, new Timestamp(System.currentTimeMillis()), ip.isTeamOne
//    )))).toRight(InternalServerError("Internal server error adding result"))
  }

  private def updateStats(newStats: List[(Points, Long)], league: League): Either[Result, Any] = {
    Try(
      newStats.foreach({ case (s, pickeeId) => {
        val pickeeStat = AppDB.pickeeStatTable.where(
          ps => ps.statFieldId === s.pointsFieldId and ps.pickeeId === pickeeId
        ).single
        println(pickeeStat)
        // has both the specific day and the overall entry
        val pickeeStats = AppDB.pickeeStatDailyTable.where(
          psd => psd.pickeeStatId === pickeeStat.id and (psd.day === league.currentDay or psd.day.isNull)
        )
        AppDB.pickeeStatDailyTable.update(pickeeStats.map(ps => {ps.value += s.value; ps}))
        val leagueUsers = from(AppDB.leagueUserTable)(lu =>
          where(lu.leagueId === league.id and (pickeeId in lu.team.map(_.pickeeId)))
            select(lu.id)
        )
        val leagueUserStat = AppDB.leagueUserStatTable.where(
          lus => lus.leagueUserId in leagueUsers and lus.statFieldId === s.pointsFieldId
        )
        val leagueUserStats = AppDB.leagueUserStatDailyTable.where(
          lud => lud.leagueUserStatId in leagueUserStat.map(_.id) and (lud.day === league.currentDay or lud.day.isNull)
        )
        AppDB.leagueUserStatDailyTable.update(leagueUserStats.map(ps => {ps.value += s.value; ps}))
        true // whats a good thing to put here
        // now update league user points if pickee in team
    }})).toOption.toRight(InternalServerError("Internal server error updating stats"))

  }

  def getReq(leagueId: String) = Action.async { implicit request =>
    Future{
      inTransaction {
        (for {
          leagueId <- parseIntId(leagueId, "League")
          league <- AppDB.leagueTable.lookup(leagueId.toInt).toRight(BadRequest("League does not exist"))
          day <- tryOrResponse[Option[Int]](() => request.getQueryString("day").map(_.toInt), BadRequest("Invalid day format"))
          success = "Successfully added results"
        } yield success).fold(identity, Created(_))
        //Future{Ok(views.html.index())}
      }
    }
  }
}
