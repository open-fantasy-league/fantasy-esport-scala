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

case class FullResultRow(externalMatchId: Long, teamOne: String, teamTwo: String, teamOneVictory: Boolean, tournamentId: Long,
                         startTstamp: LocalDateTime, addedDBTstamp: LocalDateTime,
                         targetedAtTstamp: LocalDateTime, period: Int, resultId: Long, isTeamOne: Boolean, statsValue: Double,
                         statFieldName: String, externalPickeeId: Long, pickeeName: String, pickeeCost: BigDecimal)

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
  def insertResult(matchId: Long, pickee: InternalPickee)(implicit c: Connection): Long
  def insertStats(resultId: Long, statFieldId: Long, stats: Double, pickeeId: Long)(implicit c: Connection): Long
}

@Singleton
class ResultRepoImpl @Inject()()(implicit ec: ResultExecutionContext) extends ResultRepo{
  val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
  val fullResultParser: RowParser[FullResultRow] = Macro.namedParser[FullResultRow](ColumnNaming.SnakeCase)

  override def get(leagueId: Long, period: Option[Int])(implicit c: Connection): Iterable[ResultsOut] = {
    val q =
      """
        | select m.external_match_id, m.team_one, m.team_two, m.team_one_victory, m.tournament_id, m.start_time, m.added_time,
        | m.targeted_at_time, m.period, result_id, r.is_team_one, s.value as stats_value, sf.name as stat_field_name,
        |  pck.external_pickee_id,
        |  pck.pickee_name, pck.cost as pickee_cost
        |  from matchu m join resultu r using(match_id)
        | join stats s using(result_id)
        | join stat_field sf on (sf.stat_field_id = s.stat_field_id)
        | join pickee pck using(pickee_id)
        | where m.league_id = {leagueId} and ({period} is null or m.period = {period})
        | order by m.targeted_at_tstamp desc, s.value;
      """.stripMargin
    val r = SQL(q).on("leagueId" -> leagueId, "period" -> period).as(fullResultParser.*)
    // TODO period filter
    // TODO filter by league
//    val queryRaw = from(matchTable, resultTable, pointsTable, leagueStatFieldTable, pickeeTable)(
//      (m, r, p, s, pck) => where(r.matchId === m.id and p.resultId === r.id and p.pointsFieldId === s.id and r.pickeeId === pck.id and (m.period === period .?))
//      select((m, r, p, s, pck))
//      orderBy(m.targetedAtTstamp desc, p.value asc)
//    )
//    val query = queryRaw.map(q => ResultQuery(q._1, q._2, q._3, q._4, q._5))
    resultQueryExtractor(r)
//    resultQueryExtractor(r.map(q => {
//      ResultQuery(MatchRow(q.externalMatchId, q.teamOne, q.teamTwo, q.teamOneVictory, q.tournamentId,
//        q.startTime, q.addedTime,
//        q.targetedAtTime, q.period), ResultRow(q.resultId, q.isTeamOne), StatsRow(q.pointsValue),
//        LeagueStatFieldRow(q.statFieldName), PickeeRow(q.externalPickeeId, q.pickeeName, q.pickeeCost, true))
//    }))
  }

  override def resultQueryExtractor(query: Iterable[FullResultRow]): Iterable[ResultsOut] = {
    val grouped = query.groupByOrdered(_.externalMatchId)
    grouped.map({case (externalMatchId, v) =>
      val results = v.groupByOrdered(tup => (tup.resultId, tup.externalPickeeId)).map({
        case ((resultId, externalPickeeId), x) => SingleResult(x.head.isTeamOne, x.head.pickeeName, x.map(y => y.statFieldName -> y.statsValue).toMap)
      })//(collection.breakOut): List[SingleResult]
      ResultsOut(MatchRow(externalMatchId, v.head.period, v.head.tournamentId, v.head.teamOne, v.head.teamTwo, v.head.teamOneVictory,
        v.head.startTstamp, v.head.addedDBTstamp, v.head.targetedAtTstamp), results)
    })
  }

  override def insertMatch(
                            leagueId: Long, period: Int, input: ResultFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                          )(implicit c: Connection): Long = {
    SQL(
      s"""
        |insert into matchu(league_id, external_match_id, period, tournament_id, team_one, team_two, team_one_victory,
        |start_tstamp, added_tstamp, targeted_at_tstamp)
        |VALUES($leagueId, ${input.matchId}, $period, ${input.tournamentId}, '${input.teamOne}', '${input.teamTwo}',
        | ${input.teamOneVictory}, '${input.startTstamp}', '$now', '$targetedAtTstamp') returning match_id
      """.stripMargin).executeInsert().get
  }

  override def insertResult(matchId: Long, pickee: InternalPickee)(implicit c: Connection): Long = {
    SQL(s"insert into resultu(match_id, pickee_id, is_team_one) values($matchId, ${pickee.id}, ${pickee.isTeamOne}) returning result_id;").executeInsert().get
  }

  override def insertStats(resultId: Long, statFieldId: Long, stats: Double, pickeeId: Long)(implicit c: Connection): Long = {
    SQL(
      "insert into stat(result_id, stat_field_id, value, pickee_id) values({resultId}, {statFieldId}, {stats}, {pickeeId}) returning stat_id"
    ).on("resultId" -> resultId, "statFieldId" -> statFieldId, "stats" -> stats, "pickeeId" -> pickeeId).executeInsert().get
  }

}

