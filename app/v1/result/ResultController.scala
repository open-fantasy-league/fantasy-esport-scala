package v1.result

import java.sql.{Connection, Timestamp}

import javax.inject.Inject
import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms.{sqlTimestamp, _}
import play.api.libs.json._
import play.api.data.format.Formats._
import models._
import models.AppDB._
import anorm._
import play.api.db._
import utils.TryHelper.tryOrResponse
import auth._

case class ResultFormInput(
                            matchId: Long, tournamentId: Long, teamOne: String, teamTwo: String, teamOneVictory: Boolean,
                            startTstamp: Timestamp, targetAtTstamp: Option[Timestamp], pickees: List[PickeeFormInput]
                          )

case class PickeeFormInput(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

case class InternalPickee(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

case class StatsFormInput(field: String, value: Double)

class ResultController @Inject()(db: Database, cc: ControllerComponents, resultRepo: ResultRepo, Auther: Auther)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[ResultFormInput] = {

    Form(
      mapping(
        "matchId" -> of(longFormat),
        "tournamentId" -> of(longFormat),
        "teamOne" -> nonEmptyText,
        "teamTwo" -> nonEmptyText,
        "teamOneVictory" -> boolean,
        "startTstamp" -> sqlTimestamp("yyyy-MM-dd HH:mm:ss"),
        "targetAtTstamp" -> optional(sqlTimestamp("yyyy-MM-dd HH:mm:ss")),
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

  def add(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.                          PermissionCheckAction).async{ implicit request =>
    processJsonResult(request.league)
  }

  private def processJsonResult[A](league: League)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[ResultFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: ResultFormInput) = {
      Future {
        var error: Option[Result] = None
        try {
          db.withConnection { implicit c =>
            inTransaction {
              (for {
                validateStarted <- if (league.started) Right(true) else Left(BadRequest("Cannot add results before league started"))
                internalPickee = convertExternalToInternalPickeeId(input.pickees, league)
                now = new Timestamp(System.currentTimeMillis())
                insertedMatch <- newMatch(input, league, now)
                insertedResults <- newResults(input, league, insertedMatch, internalPickee)
                insertedStats <- newStats(league, insertedMatch.id, internalPickee)
                correctPeriod <- getPeriod(input, league, now)
                updatedStats <- updateStats(c, insertedStats, league, correctPeriod)
                success = "Successfully added results"
              } yield success).fold(l => {
                error = Some(l); c.rollback(); throw new Exception("fuck")
              }, Created(_))
            }
          }
        } catch {
          case _: Throwable => error.get
        }
      }
    }

    form.bindFromRequest().fold(failure, success)
  }

  private def convertExternalToInternalPickeeId(pickees: List[PickeeFormInput], league: League): List[InternalPickee] = {
    pickees.map(ip => {
      val internalId = pickeeTable.where(p => p.leagueId === league.id and p.externalId === ip.id).single.id
      InternalPickee(internalId, ip.isTeamOne, ip.stats)
    })
  }

  private def getPeriod(input: ResultFormInput, league: League, now: Timestamp): Either[Result, Int] = {
    println(league.applyPointsAtStartTime)
    println(input.targetAtTstamp)
    println(now)
    println(input.startTstamp)

    val targetedAtTstamp = (input.targetAtTstamp, league.applyPointsAtStartTime) match {
      case (Some(x), _) => x
      case (_, true) => input.startTstamp
      case _ => now
    }
    tryOrResponse(() => {from(periodTable)(
      p => where(targetedAtTstamp > p.start and p.leagueId === league.id and targetedAtTstamp <= p.end)
        select p
    ).single.value}, InternalServerError("Cannot add result outside of period"))
  }

  private def newMatch(input: ResultFormInput, league: League, now: Timestamp): Either[Result, Matchu] = {
      // targetedAt overrides applyAtStart
    val targetedAtTstamp = (input.targetAtTstamp, league.applyPointsAtStartTime) match {
      case (Some(x), _) => x
      case (_, true) => input.startTstamp
      case _ => now
    }
    tryOrResponse[Matchu](() => matchTable.insert(new Matchu(
      league.id, input.matchId, league.currentPeriod.getOrElse(new Period()).value, input.tournamentId, input.teamOne, input.teamTwo,
      input.teamOneVictory, input.startTstamp, now, targetedAtTstamp
    )), InternalServerError("Internal server error adding match"))
  }

  private def newResults(input: ResultFormInput, league: League, matchu: Matchu, pickees: List[InternalPickee]): Either[Result, List[Resultu]] = {
    // TODO log/get original stack trace
    val newRes = pickees.map(p => new Resultu(
      matchu.id, p.id, p.isTeamOne
    ))
    tryOrResponse(() => {resultTable.insert(newRes); newRes}, InternalServerError("Internal server error adding result"))
  }

  private def newStats(league: League, matchId: Long, pickees: List[InternalPickee]): Either[Result, List[(Points, Long)]] = {
    // doing add results, add points to pickee, and to league user all at once
    // (was not like this in python)
    // as learnt about postgreq MVCC which means transactions sees teams as they where when transcation started
    // i.e. avoidss what i was worried about where if user transferred a hero midway through processing, maybe they can
    // score points from hero they were selling, then also hero they were buying, with rrace condition
    // but this isnt actually an issue https://devcenter.heroku.com/articles/postgresql-concurrency
    val newStats = pickees.flatMap(ip => ip.stats.map(s => {
      val result = resultTable.where(
        r => r.matchId === matchId and r.pickeeId === ip.id
        ).single
      val points = if (s.field == "points") s.value * league.currentPeriod.get.multiplier else s.value
      (new Points(result.id, leagueStatFieldTable.where(pf => pf.leagueId === league.id and pf.name === s.field).single.id, points),
        ip.id)
    }))
    tryOrResponse(() => {pointsTable.insert(newStats.map(_._1)); newStats}, InternalServerError("Internal server error adding result"))
  }

  private def updateStats(implicit c: Connection, newStats: List[(Points, Long)], league: League, period: Int): Either[Result, Any] = {
    tryOrResponse(() =>
      newStats.foreach({ case (s, pickeeId) => {
        val pickeeStat = pickeeStatTable.where(
          ps => ps.statFieldId === s.pointsFieldId and ps.pickeeId === pickeeId
        ).single
        // has both the specific period and the overall entry
        val pickeeStats = pickeeStatDailyTable.where(
          psd => psd.pickeeStatId === pickeeStat.id and (psd.period === period or psd.period.isNull)
        )
        pickeeStatDailyTable.update(pickeeStats.map(ps => {ps.value += s.value; ps}))
          val q =
            s"""update league_user_stat_daily lusd set value = value + {newPoints} from useru u
         join league_user lu on (u.id = lu.user_id)
         join league l on (l.id = lu.league_id)
         join team t on (t.league_user_id = lu.id and upper(t.timespan) is NULL)
         join team_pickee tp on (tp.team_id = t.id)
         join pickee p on (p.id = tp.pickee_id and p.id = {pickeeId})
         join league_user_stat lus on (lus.league_user_id = lu.id and lus.stat_field_id = {statFieldId})
         where (period is NULL or period = {period}) and lus.id = lusd.league_user_stat_id and
          (select count(*) from team_pickee where team_pickee.team_id = t.id) = l.team_size;
         """
          SQL(q).on("newPoints" -> s.value, "period" -> period, "pickeeId" -> pickeeId, "statFieldId" -> s.pointsFieldId).executeUpdate()
    }}), InternalServerError("Internal server error updating stats"))
  }

  def getReq(leagueId: String) = (new LeagueAction( leagueId)).async { implicit request =>
    Future{
      inTransaction {
        (for {
          period <- tryOrResponse[Option[Int]](() => request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          results = resultRepo.get(period).toList
          success = Ok(Json.toJson(results))
        } yield success).fold(identity, identity)
      }
    }
  }
}
