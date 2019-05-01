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
import anorm.{Macro, RowParser}
import Macro.ColumnNaming
import play.api.db._
import utils.TryHelper.{tryOrResponse, tryOrResponseRollback}
import auth._
import play.api.Logger
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo

case class ResultFormInput(
                            matchId: Long, tournamentId: Long, teamOne: String, teamTwo: String, teamOneVictory: Boolean,
                            startTstamp: LocalDateTime, targetAtTstamp: Option[LocalDateTime], pickees: List[PickeeFormInput]
                          )

case class PickeeFormInput(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

case class InternalPickee(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

case class StatsFormInput(field: String, value: Double)

class ResultController @Inject()(cc: ControllerComponents, resultRepo: ResultRepo, pickeeRepo: PickeeRepo, Auther: Auther)
                                (implicit ec: ExecutionContext, leagueRepo: LeagueRepo, db: Database) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers
  private val logger = Logger(getClass)
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

  def add(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction).async{ implicit request =>
    db.withConnection { implicit c =>processJsonResult(request.league)}
  }

  private def processJsonResult[A](league: LeagueRow)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[ResultFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: ResultFormInput) = {
      Future {
        //var error: Option[Result] = None
        db.withTransaction { implicit c =>
          (for {
            validateStarted <- if (leagueRepo.isStarted(league)) Right(true) else Left(BadRequest("Cannot add results before league started"))
            internalPickee = input.pickees.map(p => InternalPickee(pickeeRepo.getInternalId(league.leagueId, p.id).get, p.isTeamOne, p.stats))
            now = LocalDateTime.now
            targetTstamp = getTargetedAtTstamp(input, league, now)
            correctPeriod <- getPeriod(input, league, targetTstamp)
            insertedMatch <- newMatch(input, league, correctPeriod, targetTstamp, now)
            insertedResults <- newResults(input, league, insertedMatch, internalPickee)
            insertedStats <- newStats(league, insertedResults.toList, internalPickee)
            updatedStats <- updateStats(insertedStats, league, correctPeriod, targetTstamp)
          } yield Created("Successfully added results")).fold(identity, identity)
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
    tryOrResponse(() => {leagueRepo.getPeriodFromTimestamp(league.leagueId, targetedAtTstamp).get.value}, InternalServerError("Cannot add result outside of period"))
  }

  private def getTargetedAtTstamp(input: ResultFormInput, league: LeagueRow, now: LocalDateTime): LocalDateTime = {
    (input.targetAtTstamp, league.applyPointsAtStartTime) match {
      case (Some(x), _) => x
      case (_, true) => input.startTstamp
      case _ => now
    }
  }

  private def newMatch(input: ResultFormInput, league: LeagueRow, period: Int, targetAt: LocalDateTime, now: LocalDateTime)
                      (implicit c: Connection): Either[Result, Long] = {
    tryOrResponse[Long](() => resultRepo.insertMatch(
      league.leagueId, period, input, now, targetAt
    ), InternalServerError("Internal server error adding match"))
  }

  private def newResults(input: ResultFormInput, league: LeagueRow, matchId: Long, pickees: List[InternalPickee])
                        (implicit c: Connection): Either[Result, Iterable[Long]] = {
    tryOrResponseRollback(() => pickees.map(p => resultRepo.insertResult(matchId, p)), c, InternalServerError("Internal server error adding result"))
  }

  private def newStats(league: LeagueRow, resultIds: List[Long], pickees: List[InternalPickee])
                      (implicit c: Connection): Either[Result, List[(StatsRow, Long)]] = {
    tryOrResponseRollback(() => {
      // TODO tidy same code in branches
      if (league.manuallyCalculatePoints) {
        pickees.zip(resultIds).flatMap({ case (ip, resultId) => ip.stats.map(s => {
          val stats = if (s.field == "points") s.value * leagueRepo.getCurrentPeriod(league).get.multiplier else s.value
          val statFieldId = leagueRepo.getStatFieldId(league.leagueId, s.field).get
          (StatsRow(resultRepo.insertStats(resultId, statFieldId, stats), statFieldId, s.value), ip.id)
        })})
      }
      else {
        pickees.zip(resultIds).flatMap({ case (ip, resultId) =>
          val pointsStatFieldId = leagueRepo.getStatFieldId(league.leagueId, "points").get
          var pointsTotal = 0.0
          ip.stats.map(s => {
            val statFieldId = leagueRepo.getStatFieldId(league.leagueId, s.field).get
            val limitIds = pickeeRepo.getPickeeLimitIds(ip.id)
            pointsTotal += s.value * leagueRepo.getCurrentPeriod(league).get.multiplier * leagueRepo.getPointsForStat(
              statFieldId, limitIds
            )
            (StatsRow(resultRepo.insertStats(resultId, statFieldId, s.value), statFieldId, s.value), ip.id)
          }) ++ Seq((StatsRow(resultRepo.insertStats(resultId, pointsStatFieldId, pointsTotal), pointsStatFieldId, pointsTotal), ip.id))
        })

      }
    }, c, InternalServerError("Internal server error adding result"))
  }

  private def updateStats(newStats: List[(StatsRow, Long)], league: LeagueRow, period: Int, targetedAtTstamp: LocalDateTime)
                         (implicit c: Connection): Either[Result, Any] = {
    // dont need to worry about race conditions, because we use targetat tstamp, so it always updates against a consistent team
    // i.e. transfer between update calls, cant let it add points for hafl of one team, then half of a another for other update call
    // TODO can we just remove the transfer table if we're allowing future teams, with future timespans to exist?
    tryOrResponseRollback(() =>
      newStats.foreach({ case (s, pickeeId) => {
        logger.info(s"Updating stats: $s, ${s.value} for internal pickee id: $pickeeId")
        println(s"Updating stats: $s, ${s.value} for internal pickee id: $pickeeId. targeting $targetedAtTstamp")
        val pickeeQ =
          s"""update pickee_stat_period psd set value = value + {newStats} from pickee p
         join pickee_stat ps using(pickee_id)
         where (period is NULL or period = {period}) and ps.pickee_stat_id = psd.pickee_stat_id and
          p.pickee_id = {pickeeId} and ps.stat_field_id = {statFieldId} and league_id = {leagueId};
         """
        SQL(pickeeQ).on(
          "newStats" -> s.value, "period" -> period, "pickeeId" -> pickeeId, "statFieldId" -> s.statFieldId,
          "targetedAtTstamp" -> targetedAtTstamp, "leagueId" -> league.leagueId
        ).executeUpdate()
          val q =
            s"""update user_stat_period usp set value = value + {newStats} from useru u
         join league l using(league_id)
         join team t using(user_id)
         join pickee p using(pickee_id)
         join user_stat us using(user_id)
         where (period is NULL or period = {period}) and us.user_stat_id = usp.user_stat_id and
                t.timespan @> {targetedAtTstamp}::timestamptz and p.pickee_id = {pickeeId} and us.stat_field_id = {statFieldId}
               and l.league_id = {leagueId};
              """
          //(select count(*) from team_pickee where team_pickee.team_id = t.team_id) = l.team_size;
          val sql = SQL(q).on(
            "newStats" -> s.value, "period" -> period, "pickeeId" -> pickeeId, "statFieldId" -> s.statFieldId,
            "targetedAtTstamp" -> targetedAtTstamp, "leagueId" -> league.leagueId
          )
          //println(sql.sql)
           sql.executeUpdate()
        //println(changed)
    }}), c, InternalServerError("Internal server error updating stats"))
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
