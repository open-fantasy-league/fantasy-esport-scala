package v1.result

import java.sql.Connection
import java.time.LocalDateTime
import akka.actor.ActorSystem
import play.api.libs.json._
import play.api.libs.concurrent.CustomExecutionContext

import models._
import utils.GroupByOrderedImplicit._
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import javax.inject.{Inject, Singleton}

class ResultExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class FullResultRow(externalMatchId: Long, teamOne: String, teamTwo: String, teamOneVictory: Boolean, outcome: String,
                         tournamentId: Long,
                         startTstamp: LocalDateTime, addedDbTstamp: LocalDateTime,
                         targetedAtTstamp: LocalDateTime, period: Int, resultId: Long, isTeamOne: Boolean, statsValue: Double,
                         statFieldName: String, externalPickeeId: Long, pickeeName: String, pickeePrice: BigDecimal)

case class SingleResult(isTeamOne: Boolean, pickeeName: String, results: Map[String, Double])
object SingleResult{
  implicit val implicitWrites = new Writes[SingleResult]{
    def writes(r: SingleResult): JsValue = {
      Json.obj(
        "isTeamOne" -> r.isTeamOne,
        "pickee" -> r.pickeeName,
        "stats" -> r.results,
      )
    }
  }
}

case class ResultsOut(matchu: MatchRow, results: Iterable[SingleResult])
object ResultsOut{
  implicit val implicitWrites = new Writes[ResultsOut] {
    def writes(r: ResultsOut): JsValue = {
      Json.obj(
        "match" -> r.matchu,
        "results" -> r.results,
      )
    }
  }
}


trait ResultRepo{
  def get(leagueId: Long, period: Option[Int])(implicit c: Connection): Iterable[ResultsOut]
  def resultQueryExtractor(query: Iterable[FullResultRow]): Iterable[ResultsOut]
  def insertMatch(
                   leagueId: Long, period: Int, input: ResultFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                 )(implicit c: Connection): Long
  def insertFutureMatch(
                   leagueId: Long, period: Int, input: FixtureFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                 )(implicit c: Connection): Long
  def insertResult(matchId: Long, pickee: InternalPickee)(implicit c: Connection): Long
  def insertStats(resultId: Long, statFieldId: Long, stats: Double)(implicit c: Connection): Long
}

@Singleton
class ResultRepoImpl @Inject()()(implicit ec: ResultExecutionContext) extends ResultRepo{
  val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
  val fullResultParser: RowParser[FullResultRow] = Macro.namedParser[FullResultRow](ColumnNaming.SnakeCase)

  override def get(leagueId: Long, period: Option[Int])(implicit c: Connection): Iterable[ResultsOut] = {
    val q =
      """
        | select m.external_match_id, m.team_one, m.team_two, m.team_one_victory, m.outcome, m.tournament_id, m.start_tstamp, m.added_db_tstamp,
        | m.targeted_at_tstamp, m.period, result_id, r.is_team_one, s.value as stats_value, sf.name as stat_field_name,
        |  pck.external_pickee_id,
        |  pck.pickee_name, pck.price as pickee_price
        |  from matchu m join resultu r using(match_id)
        | join stat s using(result_id)
        | join stat_field sf on (sf.stat_field_id = s.stat_field_id)
        | join pickee pck using(pickee_id)
        | where m.league_id = {leagueId} and ({period} is null or m.period = {period})
        | order by m.targeted_at_tstamp desc, s.value;
      """.stripMargin
    val r = SQL(q).on("leagueId" -> leagueId, "period" -> period).as(fullResultParser.*)
    resultQueryExtractor(r)
  }

  override def resultQueryExtractor(query: Iterable[FullResultRow]): Iterable[ResultsOut] = {
    val grouped = query.groupByOrdered(_.externalMatchId)
    grouped.map({case (externalMatchId, v) =>
      val results = v.groupByOrdered(tup => (tup.resultId, tup.externalPickeeId)).map({
        case ((resultId, externalPickeeId), x) => SingleResult(x.head.isTeamOne, x.head.pickeeName, x.map(y => y.statFieldName -> y.statsValue).toMap)
      })//(collection.breakOut): List[SingleResult]
      ResultsOut(MatchRow(externalMatchId, v.head.period, v.head.tournamentId, v.head.teamOne, v.head.teamTwo,
        v.head.teamOneVictory, v.head.outcome,
        v.head.startTstamp, v.head.addedDbTstamp, v.head.targetedAtTstamp), results)
    })
  }

  override def insertMatch(
                            leagueId: Long, period: Int, input: ResultFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                          )(implicit c: Connection): Long = {
    SQL(
      s"""
        |insert into matchu(league_id, external_match_id, period, tournament_id, team_one, team_two, team_one_victory, outcome,
        |start_tstamp, added_db_tstamp, targeted_at_tstamp)
        |VALUES($leagueId, ${input.matchId}, $period, ${input.tournamentId}, '${input.teamOne}', '${input.teamTwo}',
        | ${input.teamOneVictory}, '${input.outcome}', '${input.startTstamp}', '$now', '$targetedAtTstamp') returning match_id
      """.stripMargin).executeInsert().get
  }

  override def insertFutureMatch(
                            leagueId: Long, period: Int, input: FixtureFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                          )(implicit c: Connection): Long = {
    SQL(
      s"""
         |insert into matchu(league_id, external_match_id, period, tournament_id, team_one, team_two, team_one_victory,
         |outcome, start_tstamp, added_db_tstamp, targeted_at_tstamp)
         |VALUES($leagueId, ${input.matchId}, $period, ${input.tournamentId}, '${input.teamOne}', '${input.teamTwo}',
         | null, null, '${input.startTstamp}', '$now', '$targetedAtTstamp') returning match_id
      """.stripMargin).executeInsert().get
  }

  override def insertResult(matchId: Long, pickee: InternalPickee)(implicit c: Connection): Long = {
    SQL(s"insert into resultu(match_id, pickee_id, is_team_one) values($matchId, ${pickee.id}, ${pickee.isTeamOne}) returning result_id;").executeInsert().get
  }

  override def insertStats(resultId: Long, statFieldId: Long, stats: Double)(implicit c: Connection): Long = {
    SQL(
      "insert into stat(result_id, stat_field_id, value) values({resultId}, {statFieldId}, {stats}) returning stat_id"
    ).on("resultId" -> resultId, "statFieldId" -> statFieldId, "stats" -> stats).executeInsert().get
  }

}

