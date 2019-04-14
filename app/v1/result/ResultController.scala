package v1.result

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import models._
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import play.api.db._
import utils.TryHelper.tryOrResponse
import auth._
import v1.league.LeagueRepo

case class ResultFormInput(
                            matchId: Long, tournamentId: Long, teamOne: String, teamTwo: String, teamOneVictory: Boolean,
                            startTstamp: LocalDateTime, targetAtTstamp: Option[LocalDateTime], pickees: List[PickeeFormInput]
                          )

case class PickeeFormInput(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

case class InternalPickee(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

case class StatsFormInput(field: String, value: Double)

class ResultController @Inject()(cc: ControllerComponents, resultRepo: ResultRepo, Auther: Auther)
                                (implicit ec: ExecutionContext, leagueRepo: LeagueRepo, db: Database) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[ResultFormInput] = {

    Form(
      mapping(
        "matchId" -> of(longFormat),
        "tournamentId" -> of(longFormat),
        "teamOne" -> nonEmptyText,
        "teamTwo" -> nonEmptyText,
        "teamOneVictory" -> boolean,
        "startTstamp" -> of(localDateTimeFormat("yyyy-MM-dd HH:mm:ss")),
        "targetAtTstamp" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm:ss"))),
        "pickees" -> list(mapping(
          "id" -> of(longFormat),
          "isTeamOne" -> boolean,
          "stats" -> list(mapping(
            "field" -> nonEmptyText,
            "value" -> of(doubleFormat)
          )(StatsFormInput.apply)(StatsFormInput.unapply))
        )(PickeeFormInput.apply)(PickeeFormInput.unapply))
      )(ResultFormInput.apply)(ResultFormInput.unapply)
    )
  }
  implicit val parser = parse.default
  //implicit val db_impl = db
  //implicit val lr = leagueRepo //TODO can this just go in 2nd constructor args

  def add(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction).async{ implicit request =>
    db.withConnection { implicit c =>processJsonResult(request.league)}
  }

  private def processJsonResult[A](league: LeagueRow)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[ResultFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: ResultFormInput) = {
      Future {
        var error: Option[Result] = None
        try {
          db.withConnection { implicit c =>
              (for {
                validateStarted <- if (leagueRepo.isStarted(league)) Right(true) else Left(BadRequest("Cannot add results before league started"))
                internalPickee = input.pickees.map(p => pickeeRepo.getInternalId(league.leagueId, p.pickeeId)
                now: LocalDateTime = LocalDateTime.now
                targetTstamp = getTargetedAtTstamp(input, league, now)
                correctPeriod <- getPeriod(input, league, targetTstamp)
                insertedMatch <- newMatch(input, league, correctPeriod, targetTstamp)
                insertedResults <- newResults(input, league, insertedMatch, internalPickee)
                insertedStats <- newStats(league, insertedResults.toList, internalPickee)
                updatedStats <- updateStats(insertedStats, league, correctPeriod, insertedMatch.targetedAtTstamp)
                success = "Successfully added results"
              } yield success).fold(l => {
                error = Some(l); c.rollback(); throw new Exception("fuck")
              }, Created(_))
          }
        } catch {
          case _: Throwable => error.get
        }
      }
    }

    form.bindFromRequest().fold(failure, success)
  }

  private def getPeriod(input: ResultFormInput, league: LeagueRow, now: LocalDateTime)(implicit c: Connection): Either[Result, Int] = {
    println(league.applyPointsAtStartTime)
    println(input.targetAtTstamp)
    println(now)
    println(input.startTstamp)

    val targetedAtTstamp = (input.targetAtTstamp, league.applyPointsAtStartTime) match {
      case (Some(x), _) => x
      case (_, true) => input.startTstamp
      case _ => now
    }
    tryOrResponse(() => {leagueRepo.getPeriodBetween(league.leagueId, targetedAtTstamp).get.value}, InternalServerError("Cannot add result outside of period"))
  }

  private def getTargetedAtTstamp(input: ResultFormInput, league: LeagueRow, now: LocalDateTime): LocalDateTime = {
    (input.targetAtTstamp, league.applyPointsAtStartTime) match {
      case (Some(x), _) => x
      case (_, true) => input.startTstamp
      case _ => now
    }
  }

  private def newMatch(input: ResultFormInput, league: LeagueRow, period: Int, targetAt: LocalDateTime)(implicit c: Connection): Either[Result, Long] = {
    tryOrResponse[Matchu](() => resultRepo.insertMatch(
      league.leagueId, period, input, now, targetAt
    ), InternalServerError("Internal server error adding match"))
  }

  private def newResults(input: ResultFormInput, league: LeagueRow, matchId: Long, pickees: List[InternalPickee])(implicit c: Connection): Either[Result, List[Resultu]] = {
    tryOrResponse(() => pickees.map(p => resultRepo.insertResult(matchId, p)), InternalServerError("Internal server error adding result"))
  }

  private def newStats(league: LeagueRow, resultIds: List[Long], pickees: List[InternalPickee])(implicit c: Connection): Either[Result, List[(StatsRow, Long)]] = {
    // doing add results, add stats to pickee, and to league user all at once
    // (was not like this in python)
    // as learnt about postgreq MVCC which means transactions sees teams as they where when transcation started
    // i.e. avoidss what i was worried about where if user transferred a hero midway through processing, maybe they can
    // score stats from hero they were selling, then also hero they were buying, with rrace condition
    // but this isnt actually an issue https://devcenter.heroku.com/articles/postgresql-concurrency
    tryOrResponse(() => {
      val newStats = pickees.zip(resultIds).map((ip, resultId) => ip.stats.map(s => {
        val stats = if (s.field == "points") s.value * leagueRepo.getCurrentPeriod(league).get.multiplier else s.value
        resultRepo.insertStats(resultId, leagueRepo.getStatFieldId(league.leagueId, s.name).get, stats, ip.pickeeId)
      }))
    }, InternalServerError("Internal server error adding result"))
  }

  private def updateStats(newStats: List[(StatsRow, Long)], league: LeagueRow, period: Int, targetedAtTstamp: LocalDateTime)(implicit c: Connection): Either[Result, Any] = {
    tryOrResponse(() =>
      newStats.foreach({ case (s, pickeeId) => {
        val pickeeQ =
          s"""update pickee_stat_daily psd set value = value + {newStats} from pickee p
         join league_user lu using(user_id)
         join league l using(league_id)
         join pickee_stat ps using(pickee_id)
         where (period is NULL or period = {period}) and ps.pickee_stat_id = psd.pickee_stat_id and
             |p.pickee_id = {pickeeId} and ps.stat_field_id = {statFieldId} and league_id = {leagueId};
         """
        SQL(pickeeQ).on(
          "newStats" -> s.value, "period" -> period, "pickeeId" -> pickeeId, "statFieldId" -> s.statsFieldId,
          "targetedAtTstamp" -> targetedAtTstamp, "leagueId" -> league.leagueId
        ).executeUpdate()
          val q =
            s"""update league_user_stat_daily lusd set value = value + {newStats} from useru u
         join league_user lu using(user_id)
         join league l using(league_id)
         join team t using(league_user_id)
         join pickee p using(pickee_id)
         join league_user_stat lus using(league_user_id)
         where (period is NULL or period = {period}) and lus.league_user_stat_id = lusd.league_user_stat_id and
               |t.timespan @> {targetedAtTstamp} and p.pickee_id = {pickeeId} and lus.stat_field_id = {statFieldId};
               |"""
          //(select count(*) from team_pickee where team_pickee.team_id = t.team_id) = l.team_size;
          SQL(q).on(
            "newStats" -> s.value, "period" -> period, "pickeeId" -> pickeeId, "statFieldId" -> s.statsFieldId,
            "targetedAtTstamp" -> targetedAtTstamp
          ).executeUpdate()
    }}), InternalServerError("Internal server error updating stats"))
  }

  def getReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future{
      db.withConnection { implicit c =>
        (for {
          period <- tryOrResponse[Option[Int]](() => request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          results = resultRepo.get(request.league.leagueId, period).toList
          success = Ok(Json.toJson(results))
        } yield success).fold(identity, identity)
      }
    }
  }
}
