package v1.result

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.Inject
import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import models._
import models.AppDB._
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
            inTransaction {
              (for {
                validateStarted <- if (leagueRepo.isStarted(league)) Right(true) else Left(BadRequest("Cannot add results before league started"))
                internalPickee = convertExternalToInternalPickeeId(input.pickees, league)
                now: LocalDateTime = LocalDateTime.now
                insertedMatch <- newMatch(input, league, now)
                insertedResults <- newResults(input, league, insertedMatch, internalPickee)
                insertedStats <- newStats(league, insertedMatch.id, internalPickee)
                correctPeriod <- getPeriod(input, league, insertedMatch.targetedAtTstamp)
                updatedStats <- updateStats(insertedStats, league, correctPeriod, insertedMatch.targetedAtTstamp)
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

  private def convertExternalToInternalPickeeId(pickees: List[PickeeFormInput], league: LeagueRow)(implicit c: Connection): List[InternalPickee] = {
    pickees.map(ip => {
      val internalId = pickeeTable.where(p => p.leagueId === league.id and p.externalId === ip.id).single.id
      InternalPickee(internalId, ip.isTeamOne, ip.stats)
    })
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
    tryOrResponse(() => {leagueRepo.getPeriodBetween(league.id, targetedAtTstamp).get.value}, InternalServerError("Cannot add result outside of period"))
  }

  private def newMatch(input: ResultFormInput, league: LeagueRow, now: LocalDateTime)(implicit c: Connection): Either[Result, Matchu] = {
      // targetedAt overrides applyAtStart
    val targetedAtTstamp = (input.targetAtTstamp, league.applyPointsAtStartTime) match {
      case (Some(x), _) => x
      case (_, true) => input.startTstamp
      case _ => now
    }
    tryOrResponse[Matchu](() => matchTable.insert(new Matchu(
      league.id, input.matchId, leagueRepo.getCurrentPeriod(league).map(_.value).getOrElse(0), input.tournamentId, input.teamOne, input.teamTwo,
      input.teamOneVictory, input.startTstamp, now, targetedAtTstamp
    )), InternalServerError("Internal server error adding match"))
  }

  private def newResults(input: ResultFormInput, league: LeagueRow, matchu: Matchu, pickees: List[InternalPickee])(implicit c: Connection): Either[Result, List[Resultu]] = {
    // TODO log/get original stack trace
    val newRes = pickees.map(p => new Resultu(
      matchu.id, p.id, p.isTeamOne
    ))
    tryOrResponse(() => {resultTable.insert(newRes); newRes}, InternalServerError("Internal server error adding result"))
  }

  private def newStats(league: LeagueRow, matchId: Long, pickees: List[InternalPickee])(implicit c: Connection): Either[Result, List[(Points, Long)]] = {
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
      val points = if (s.field == "points") s.value * leagueRepo.getCurrentPeriod(league).get.multiplier else s.value
      (new Points(result.id, leagueStatFieldTable.where(pf => pf.leagueId === league.id and pf.name === s.field).single.id, points),
        ip.id)
    }))
    tryOrResponse(() => {pointsTable.insert(newStats.map(_._1)); newStats}, InternalServerError("Internal server error adding result"))
  }

  private def updateStats(newStats: List[(Points, Long)], league: LeagueRow, period: Int, targetedAtTstamp: LocalDateTime)(implicit c: Connection): Either[Result, Any] = {
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
         join league_user lu using(user_id)
         join league l using(league_id)
         join team t using(league_user_id)
         join team_pickee tp using(team_id)
         join pickee p using(pickee_id)
         join league_user_stat lus using(league_user_id)
         where (period is NULL or period = {period}) and lus.id = lusd.league_user_stat_id and
               |t.timespan @> {targetedAtTstamp} and p.id = {pickeeId} and lus.stat_field_id = {statFieldId} and
          (select count(*) from team_pickee where team_pickee.team_id = t.team_id) = l.team_size;
         """
          SQL(q).on(
            "newPoints" -> s.value, "period" -> period, "pickeeId" -> pickeeId, "statFieldId" -> s.pointsFieldId,
            "targetedAtTstamp" -> targetedAtTstamp
          ).executeUpdate()
    }}), InternalServerError("Internal server error updating stats"))
  }

  def getReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future{
      inTransaction {
        db.withConnection { implicit c =>
          (for {
            period <- tryOrResponse[Option[Int]](() => request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
            results = resultRepo.get(request.league.id, period).toList
            success = Ok(Json.toJson(results))
          } yield success).fold(identity, identity)
        }
      }
    }
  }
}
