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

case class FullResultRow(externalMatchId: Long, teamOne: String, teamTwo: String, teamOneVictory: Option[Boolean],
                         teamOneScore: Option[Int], teamTwoScore: Option[Int],
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
  def getMatches(leagueId: Long, period: Option[Int])(implicit c: Connection): Iterable[MatchRow]
  def resultQueryExtractor(query: Iterable[FullResultRow]): Iterable[ResultsOut]
  def insertMatch(
                   leagueId: Long, period: Int, input: ResultFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                 )(implicit c: Connection): Long
  def insertFutureMatch(
                   leagueId: Long, period: Int, input: FixtureFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                 )(implicit c: Connection): Long
  def insertResult(matchId: Long, pickee: InternalPickee)(implicit c: Connection): Long
  def insertStats(resultId: Long, statFieldId: Long, stats: Double)(implicit c: Connection): Long
  def getUserPredictions(userId: Long, periodValue: Int)(implicit c: Connection): Iterable[PredictionRow]
  def upsertUserPrediction(userId: Long, leagueId: Long, externalMatchId: Long, teamOneScore: Int, teamTwoScore: Int)(
    implicit c: Connection): Either[String, PredictionRow]
  def upsertUserPredictions(userId: Long, leagueId: Long, predictions: List[PredictionFormInput])(
    implicit c: Connection): Either[String, Iterable[PredictionRow]]
  def isMatchStarted(leagueId: Long, externalMatchId: Long)(implicit c: Connection): Boolean
  def findMatchByTeams(leagueId: Long, teamOne: String, teamTwo: String)(implicit c: Connection): Iterable[MatchRow]
}

@Singleton
class ResultRepoImpl @Inject()()(implicit ec: ResultExecutionContext) extends ResultRepo{
  val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
  val fullResultParser: RowParser[FullResultRow] = Macro.namedParser[FullResultRow](ColumnNaming.SnakeCase)

  override def get(leagueId: Long, period: Option[Int])(implicit c: Connection): Iterable[ResultsOut] = {
    val q =
      """
        | select m.external_match_id, m.team_one, m.team_two, m.team_one_victory, m.team_one_score, m.team_two_score, m.tournament_id, m.start_tstamp, m.added_db_tstamp,
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

  override def getMatches(leagueId: Long, period: Option[Int])(implicit c: Connection): Iterable[MatchRow] = {
    val q =
      """
        | select m.external_match_id, m.team_one, m.team_two, m.team_one_victory, m.team_one_score, m.team_two_score,
        |  m.tournament_id, m.start_tstamp, m.added_db_tstamp,
        | m.targeted_at_tstamp, m.period
        |  from matchu m
        | where m.league_id = {leagueId} and ({period} is null or m.period = {period})
        | order by m.targeted_at_tstamp desc;
      """.stripMargin
    SQL(q).on("leagueId" -> leagueId, "period" -> period).as(MatchRow.parser.*)
  }

  override def resultQueryExtractor(query: Iterable[FullResultRow]): Iterable[ResultsOut] = {
    val grouped = query.groupByOrdered(_.externalMatchId)
    grouped.map({case (externalMatchId, v) =>
      val results = v.groupByOrdered(tup => (tup.resultId, tup.externalPickeeId)).map({
        case ((resultId, externalPickeeId), x) => SingleResult(x.head.isTeamOne, x.head.pickeeName, x.map(y => y.statFieldName -> y.statsValue).toMap)
      })//(collection.breakOut): List[SingleResult]
      ResultsOut(MatchRow(externalMatchId, v.head.period, v.head.tournamentId, v.head.teamOne, v.head.teamTwo,
        v.head.teamOneVictory, v.head.teamOneScore, v.head.teamTwoScore,
        v.head.startTstamp, v.head.addedDbTstamp, v.head.targetedAtTstamp), results)
    })
  }

  override def insertMatch(
                            leagueId: Long, period: Int, input: ResultFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                          )(implicit c: Connection): Long = {
    SQL(
      s"""
        |insert into matchu(league_id, external_match_id, period, tournament_id, team_one, team_two, team_one_score, team_two_score,
        |start_tstamp, added_db_tstamp, targeted_at_tstamp)
        |VALUES($leagueId, ${input.matchId}, $period, ${input.tournamentId}, '${input.teamOne}', '${input.teamTwo}',
        | ${input.teamOneScore}, '${input.teamTwoScore}', '${input.startTstamp}', '$now', '$targetedAtTstamp') returning match_id
      """.stripMargin).executeInsert().get
  }

  override def insertFutureMatch(
                            leagueId: Long, period: Int, input: FixtureFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                          )(implicit c: Connection): Long = {
    SQL(
      s"""
         |insert into matchu(league_id, external_match_id, period, tournament_id, team_one, team_two, team_one_score,
         |team_two_score, start_tstamp, added_db_tstamp, targeted_at_tstamp)
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

  override def getUserPredictions(userId: Long, periodVal: Int)(implicit c: Connection): Iterable[PredictionRow] = {
    SQL"""select m.external_match_id, p.team_one_score, p.team_two_score, user_id, paid_out from prediction p
         join matchu m using(match_id)
         where user_id = $userId and period = $periodVal"""
  }.as(PredictionRow.parser.*)

  override def upsertUserPrediction(
                                      userId: Long, leagueId: Long, externalMatchId: Long, teamOneScore: Int, teamTwoScore: Int
                                    )(implicit c: Connection): Either[String, PredictionRow] = {
    if (isMatchStarted(leagueId, externalMatchId)) Left(s"Match $externalMatchId already started. Prediction closed")
    else {
      Right {
        SQL"""insert into prediction(match_id, team_one_score, team_two_score, user_id)
           VALUES ((SELECT match_id from matchu where external_match_id = $externalMatchId and league_id = $leagueId LIMIT 1),
         $teamOneScore, $teamTwoScore, $userId)
         on conflict (user_id, match_id) do update
         set team_one_score = $teamOneScore, team_two_score = $teamTwoScore
         returning $externalMatchId as external_match_id, team_one_score, team_two_score, user_id, paid_out"""
          .executeInsert(PredictionRow.parser.single)
      }
    }
  }

  override def upsertUserPredictions(
                                      userId: Long, leagueId: Long, predictions: List[PredictionFormInput]
                                    )(implicit c: Connection): Either[String, Iterable[PredictionRow]] = {
    Right(predictions.map(p => {
      if (isMatchStarted(leagueId, p.matchId)) return Left(s"Match ${p.matchId} already started. Prediction closed")
      SQL"""insert into prediction(match_id, team_one_score, team_two_score, user_id)
         VALUES ((SELECT match_id from matchu where external_match_id = ${p.matchId} and league_id = $leagueId LIMIT 1),
       ${p.teamOneScore}, ${p.teamTwoScore}, $userId)
       on conflict (user_id, match_id) do update
       set team_one_score = ${p.teamOneScore}, team_two_score = ${p.teamTwoScore}
       returning ${p.matchId} as external_match_id, team_one_score, team_two_score, user_id, paid_out"""
        .executeInsert(PredictionRow.parser.single)
    }))
  }

  override def isMatchStarted(leagueId: Long, externalMatchId: Long)(implicit c: Connection): Boolean = {
    SQL"""
         select now() > start_tstamp as started from matchu where league_id = $leagueId AND external_match_id = $externalMatchId limit 1
      """.as(SqlParser.bool("started").single)
  }

  override def findMatchByTeams(leagueId: Long, teamOne: String, teamTwo: String)(implicit c: Connection): Iterable[MatchRow] = {
    // iterable as maybe there are 2 home and 2 away fixtures in the season?
    // TODO uses pretty much same query as getMatches
    val q =
      """
        | select m.external_match_id, m.team_one, m.team_two, m.team_one_victory, m.team_one_score, m.team_two_score,
        |  m.tournament_id, m.start_tstamp, m.added_db_tstamp,
        | m.targeted_at_tstamp, m.period
        |  from matchu m
        | where m.league_id = {leagueId} and m.team_one = {teamOne} and m.team_two = {teamTwo}
        | order by m.targeted_at_tstamp;
      """.stripMargin
    SQL(q).on("leagueId" -> leagueId, "teamOne" -> teamOne, "teamTwo" -> teamTwo).as(MatchRow.parser.*)

  }

}

