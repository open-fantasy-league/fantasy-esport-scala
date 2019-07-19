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

case class FullSeriesRow(externalSeriesId: Long, externalMatchId: Option[Long], teamOne: String, teamTwo: String,
                         bestOf: Int,
                         seriesStartTstamp: LocalDateTime, seriesTeamOneCurrentScore: Int, seriesTeamTwoCurrentScore: Int,
                         seriesTeamOneFinalScore: Option[Int], seriesTeamTwoFinalScore: Option[Int], matchTeamOneFinalScore: Option[Int],
                         matchTeamTwoFinalScore: Option[Int],
                         tournamentId: Long,
                         matchStartTstamp: Option[LocalDateTime], addedDbTstamp: Option[LocalDateTime],
                         targetedAtTstamp: Option[LocalDateTime], period: Int, resultId: Option[Long], isTeamOne: Option[Boolean],
                         statsValue: Option[Double],
                         statFieldName: Option[String], statFieldDescription: Option[String],
                         externalPickeeId: Option[Long], pickeeName: Option[String], pickeePrice: Option[BigDecimal])


trait ResultRepo{
  def getSeries(leagueId: Long, period: Option[Int])(implicit c: Connection): Iterable[SeriesOut]
  def insertSeries(
                    leagueId: Long, period: Int, input: SeriesFormInput, startTstamp: LocalDateTime
                  )(implicit c: Connection): Long
  //def getMatches(leagueId: Long, period: Option[Int])(implicit c: Connection): Iterable[MatchRow]
  def seriesQueryExtractor(query: Iterable[FullSeriesRow]): Iterable[SeriesOut]
  def insertMatch(
                   seriesId: Long, period: Int, input: MatchFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                 )(implicit c: Connection): Long
  def insertFutureMatch(
                   leagueId: Long, period: Int, input: FixtureFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                 )(implicit c: Connection): Long
  def insertResult(matchId: Long, pickee: InternalPickee)(implicit c: Connection): Long
  def insertStats(resultId: Long, statFieldId: Long, stats: Double)(implicit c: Connection): Long
  def getUserPredictions(userId: Long, periodValue: Int)(implicit c: Connection): Iterable[PredictionRow]
  def upsertUserPrediction(userId: Long, leagueId: Long, externalSeriesId: Option[Long], externalMatchId: Option[Long],
                           teamOneScore: Int, teamTwoScore: Int)(
    implicit c: Connection): Either[String, PredictionRow]
  def upsertUserPredictions(userId: Long, leagueId: Long, predictions: List[PredictionFormInput])(
    implicit c: Connection): Either[String, Iterable[PredictionRow]]
  def isMatchStarted(leagueId: Long, externalMatchId: Long)(implicit c: Connection): Boolean
  def isSeriesStarted(leagueId: Long, externalSeriesId: Long)(implicit c: Connection): Boolean
  def findSeriesByTeams(leagueId: Long, teamOne: String, teamTwo: String, includeReversedTeams: Boolean)
                       (implicit c: Connection): Iterable[SeriesOut]
}

@Singleton
class ResultRepoImpl @Inject()()(implicit ec: ResultExecutionContext) extends ResultRepo{
  val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
  val fullSeriesParser: RowParser[FullSeriesRow] = Macro.namedParser[FullSeriesRow](ColumnNaming.SnakeCase)

  override def getSeries(leagueId: Long, period: Option[Int])(implicit c: Connection): Iterable[SeriesOut] = {
    val q =
      """
        select m.external_match_id, ser.external_series_id, ser.team_one, ser.team_two, ser.best_of,
        ser.series_team_one_current_score, ser.series_team_two_current_score,
        ser.series_team_one_final_score, ser.series_team_two_final_score, m.match_team_one_final_score, m.match_team_two_final_score,
        ser.tournament_id, m.start_tstamp as match_start_tstamp, ser.start_tstamp as series_start_tstamp, m.added_db_tstamp,
        m.targeted_at_tstamp, ser.period, result_id, r.is_team_one, s.value as stats_value, sf.name as stat_field_name,
        sf.description as stat_field_description,
        pck.external_pickee_id,
        pck.pickee_name, pck.price as pickee_price
        from series ser left join matchu m using(series_id) left join resultu r using(match_id)
        left join stat s using(result_id)
        left join stat_field sf on (sf.stat_field_id = s.stat_field_id)
        left join pickee pck using(pickee_id)
        where ser.league_id = {leagueId} and ({period} is null or period = {period})
        order by m.start_tstamp, s.value;
      """
    val r = SQL(q).on("leagueId" -> leagueId, "period" -> period).as(fullSeriesParser.*)
    seriesQueryExtractor(r)
  }

//  override def getMatches(leagueId: Long, period: Option[Int])(implicit c: Connection): Iterable[MatchRow] = {
//    val q =
//      """
//        | select m.external_match_id, m.team_one_score, m.team_two_score,
//        | m.start_tstamp, m.added_db_tstamp,
//        | m.targeted_at_tstamp
//        |  from matchu m
//        | where m.league_id = {leagueId} and ({period} is null or m.period = {period})
//        | order by m.targeted_at_tstamp desc;
//      """.stripMargin
//    SQL(q).on("leagueId" -> leagueId, "period" -> period).as(MatchRow.parser.*)
//  }

  override def seriesQueryExtractor(query: Iterable[FullSeriesRow]): Iterable[SeriesOut] = {
    implicit def dateTimeOrdering: Ordering[LocalDateTime] = Ordering.fromLessThan(_ isBefore _)
    query.groupBy(_.externalSeriesId).map({ case (externalSeriesId, row) => {
      val head = row.head
      val matches = row.filter(_.externalMatchId.isDefined).groupBy(_.externalMatchId).map({ case (externalMatchId, row) => {
        val results = row.filter(_.resultId.isDefined).groupBy(tup => (tup.resultId.get, tup.externalPickeeId.get)).map({
          case ((resultId, externalPickeeId), x) => SingleResult(x.head.isTeamOne.get, x.head.pickeeName.get,
            x.map(y => y.statFieldName.get -> y.statsValue.get).toMap)
        })
        val head = row.head
        MatchOut(MatchRow(
          externalMatchId.get, head.matchTeamOneFinalScore, head.matchTeamTwoFinalScore, head.matchStartTstamp.get, head.addedDbTstamp.get,
          head.targetedAtTstamp.get), results)
      }}).toList.sortBy(_.matchu.startTstamp)
        SeriesOut(SeriesRow(
          head.externalSeriesId, head.period, head.tournamentId, head.teamOne, head.teamTwo, head.bestOf,
          head.seriesTeamOneCurrentScore, head.seriesTeamTwoCurrentScore, head.seriesTeamOneFinalScore,
          head.seriesTeamTwoFinalScore, head.seriesStartTstamp
        ), matches)
    }}).toList.sortBy(_.series.startTstamp)
  }

  override def insertMatch(
                            seriesId: Long, period: Int, input: MatchFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                          )(implicit c: Connection): Long = {
    SQL"""
        insert into matchu(series_id, external_match_id, match_team_one_final_score, match_team_two_final_score,
        start_tstamp, added_db_tstamp, targeted_at_tstamp)
        VALUES($seriesId, ${input.matchId},
         ${input.matchTeamOneFinalScore}, ${input.matchTeamTwoFinalScore}, ${input.startTstamp}, $now, $targetedAtTstamp) returning match_id
      """.executeInsert().get
  }

  override def insertSeries(
                            leagueId: Long, period: Int, input: SeriesFormInput, startTstamp: LocalDateTime
                          )(implicit c: Connection): Long = {
    SQL"""
         insert into series(league_id, external_series_id, period, tournament_id, team_one, team_two, best_of,
         series_team_one_current_score, series_team_two_current_score, series_team_one_final_score, series_team_two_final_score,
         start_tstamp)
         VALUES($leagueId, ${input.seriesId}, $period, ${input.tournamentId}, ${input.teamOne}, ${input.teamTwo},
         ${input.bestOf}, ${input.seriesTeamOneCurrentScore}, ${input.seriesTeamTwoCurrentScore},
          ${input.seriesTeamOneFinalScore}, ${input.seriesTeamTwoFinalScore}, $startTstamp) returning series_id
      """.executeInsert().get
  }

  override def insertFutureMatch(
                            leagueId: Long, period: Int, input: FixtureFormInput, now: LocalDateTime, targetedAtTstamp: LocalDateTime
                          )(implicit c: Connection): Long = {
    SQL(
      s"""
         |insert into matchu(league_id, external_match_id, period, tournament_id, team_one, team_two, match_team_one_final_score,
         |match_team_two_final_score, start_tstamp, added_db_tstamp, targeted_at_tstamp)
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
    SQL"""select m.external_match_id, s.external_series_id, p.team_one_score, p.team_two_score, user_id, paid_out
           from prediction p
         left join matchu m using(match_id)
         left join series s on p.series_id = s.series_id
         left join series s2 on m.series_id = s2.series_id
         where user_id = $userId and
         ((s.period is null and s2.period = $periodVal) or (s2.period is null and s.period = $periodVal))"""
  }.as(PredictionRow.parser.*)
  override def upsertUserPrediction(
                                      userId: Long, leagueId: Long, externalSeriesIdOpt: Option[Long],
                                      externalMatchIdOpt: Option[Long], teamOneScore: Int, teamTwoScore: Int
                                    )(implicit c: Connection): Either[String, PredictionRow] = {
    (externalSeriesIdOpt, externalMatchIdOpt) match {
      case (None, Some(externalMatchId)) => {
        if (isMatchStarted (leagueId, externalMatchId) ) Left (s"Match $externalMatchId already started. Prediction closed")
        else {
          Right {
            SQL"""insert into prediction(match_id, team_one_score, team_two_score, user_id)
             VALUES ((SELECT match_id from matchu join series using(series_id) where external_match_id = $externalMatchId and league_id = $leagueId LIMIT 1),
           $teamOneScore, $teamTwoScore, $userId)
           on conflict (user_id, series_id, match_id) do update
           set team_one_score = $teamOneScore, team_two_score = $teamTwoScore
           returning null as external_series_id, $externalMatchId as external_match_id, team_one_score, team_two_score, user_id, paid_out"""
              .executeInsert(PredictionRow.parser.single)
          }
        }
      }
      case (Some(externalSeriesId), None) => {
        if (isSeriesStarted (leagueId, externalSeriesId) ) Left (s"Match $externalSeriesId already started. Prediction closed")
        else {
          Right {
            SQL"""insert into prediction(series_id, team_one_score, team_two_score, user_id)
             VALUES ((SELECT series_id from series where external_series_id = $externalSeriesId and league_id = $leagueId LIMIT 1),
           $teamOneScore, $teamTwoScore, $userId)
           on conflict (user_id, series_id, match_id) do update
           set team_one_score = $teamOneScore, team_two_score = $teamTwoScore
           returning $externalSeriesId as external_series_id,
           null as external_match_id, team_one_score, team_two_score, user_id, paid_out"""
              .executeInsert(PredictionRow.parser.single)
          }
        }

      }
      case (None, None) => Left(s"Must specify either match id or series id")
      case (Some(_), Some(_)) => Left(s"Specify either matchId OR seriesId, not both.")
    }
  }

  override def upsertUserPredictions(
                                      userId: Long, leagueId: Long, predictions: List[PredictionFormInput]
                                    )(implicit c: Connection): Either[String, Iterable[PredictionRow]] = {
    val preds: List[Either[String, PredictionRow]] = predictions.map(p => {
      upsertUserPrediction(
        userId, leagueId, p.seriesId,
        p.matchId, p.teamOneScore, p.teamTwoScore
      )
    })
    preds.foldRight(Right(Nil): Either[String, List[PredictionRow]]) { (elem, acc) =>
        acc.right.flatMap(list => elem.right.map(_ :: list))}
  }

  override def isMatchStarted(leagueId: Long, externalMatchId: Long)(implicit c: Connection): Boolean = {
    SQL"""
         select now() > m.start_tstamp as started from matchu m join series using(series_id) where league_id = $leagueId AND external_match_id = $externalMatchId limit 1
      """.as(SqlParser.bool("started").single)
  }

  override def isSeriesStarted(leagueId: Long, externalSeriesId: Long)(implicit c: Connection): Boolean = {
    SQL"""
         select now() > start_tstamp as started from series where league_id = $leagueId AND external_series_id = $externalSeriesId limit 1
      """.as(SqlParser.bool("started").single)
  }

  override def findSeriesByTeams(leagueId: Long, teamOne: String, teamTwo: String, includeReversedTeams: Boolean)(
    implicit c: Connection): Iterable[SeriesOut] = {
    // iterable as maybe there are 2 home and 2 away fixtures in the season?
    // TODO uses pretty much same query as getMatches
    val q =
      """
        | select s.external_series_id, s.tournament_id, s.period, s.start_tstamp as series_start_tstamp,
        | series_team_one_final_score, series_team_two_final_score, m.external_match_id,
        | team_one, team_two, m.match_team_one_final_score, m.match_team_two_final_score,
        |  tournament_id, m.start_tstamp, m.added_db_tstamp,
        | m.targeted_at_tstamp, period
        |  from series s
        |  left join matchu m using(series_id)
        | where league_id = {leagueId} and ((team_one = {teamOne} and team_two = {teamTwo}) or
        | ({includeReversedTeams} AND team_one = {teamTwo} and team_two = {teamOne}))
        | order by m.start_tstamp;
      """.stripMargin
    val rows = SQL(q).on("leagueId" -> leagueId, "teamOne" -> teamOne, "teamTwo" -> teamTwo).as(SeriesAndMatchRow.parser.*)
    SeriesAndMatchRow.out(rows)
  }

}

