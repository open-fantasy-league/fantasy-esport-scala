package v1.league

import javax.inject.{Inject, Singleton}
import play.api.Logger
import java.sql.Connection
import java.time.LocalDateTime
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, InternalServerError}
import anorm._
import anorm.~
import anorm.{ Macro, RowParser}, Macro.ColumnNaming

import akka.actor.ActorSystem
import models._
import utils.Utils._

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class NameAndDescription(name: String, description: Option[String])
case class PostPeriodHookInfo(onEndOpenTransferWindow: Boolean, onEndEliminateUsersTo: Option[Int])

object NameAndDescription{
  implicit val implicitWrites = new Writes[NameAndDescription] {
    def writes(x: NameAndDescription): JsValue = {
      Json.obj(
        "name" -> x.name,
        "description" -> x.description
      )
    }
  }
}

case class LeagueFull(
                       league: PublicLeagueRow, limits: Map[String, Iterable[LimitRow]], periods: Iterable[PeriodRow],
                       currentPeriod: Option[PeriodRow], statFields: Iterable[NameAndDescription], scoring: Map[String, Map[String, Double]])

object LeagueFull{
  implicit val implicitWrites = new Writes[LeagueFull] {
    def writes(league: LeagueFull): JsValue = {
      Json.obj(
        "id" -> league.league.leagueId,
        "name" -> league.league.leagueName,
        "gameId" -> league.league.gameId,
        "tournamentId" -> league.league.tournamentId,
        "isPrivate" -> league.league.isPrivate,
        "tournamentId" -> league.league.tournamentId,
        "teamSize" -> league.league.teamSize,
        "benchSize" -> league.league.benchSize,
        "transferLimit" -> league.league.transferLimit, // use -1 for no transfer limit I think. only applies after period 1 start
        "transferWildcard" -> league.league.transferWildcard,
        "transferOpen" -> league.league.transferOpen,
        "forceFullTeams" -> league.league.forceFullTeams,
        "startingMoney" -> league.league.startingMoney,
        "statFields" -> league.statFields,
        "limitTypes" -> league.limits,
        "periods" -> league.periods,
        "currentPeriod" -> league.currentPeriod,
        "started" -> league.league.started,
        "ended" -> (league.currentPeriod.exists(_.ended) && league.currentPeriod.exists(_.nextPeriodId.isEmpty)),
        "pickeeDescription" -> league.league.pickeeDescription,
        "periodDescription" -> league.league.periodDescription,
        "noWildcardForLateRegister" -> league.league.noWildcardForLateRegister,
        "system" -> league.league.system,
        "recycleValue" -> league.league.recycleValue,
        "cardPackCost" -> league.league.packCost,
        "cardPackSize" -> league.league.packSize,
        "predictionWinMoney" -> league.league.predictionWinMoney,
        "applyPointsAtStartTime" -> league.league.applyPointsAtStartTime,
        "url" -> {if (league.league.urlVerified) league.league.url else ""},
        "scoring" -> league.scoring,
        "numPeriods" -> league.league.numPeriods,
        "draftStart" -> league.league.draftStart,
        "nextDraftDeadline" -> league.league.nextDraftDeadline,
        "choiceTimer" -> league.league.choiceTimer,
        "manualDraft" -> league.league.manualDraft,
        "draftPaused" -> league.league.draftPaused
      )
    }
  }
}


trait LeagueRepo{
  def get(leagueId: Long)(implicit c: Connection): Option[LeagueRow]
  def getWithRelated(leagueId: Long, showPeriods: Boolean, showScoring: Boolean, showStatfields: Boolean, showLimits: Boolean)(implicit c: Connection): LeagueFull
  def insert(formInput: LeagueFormInput)(implicit c: Connection): LeagueRow
  def update(leagueId: Long, input: UpdateLeagueFormInput)(implicit c: Connection): LeagueRow
  def getStatFields(leagueId: Long)(implicit c: Connection): Iterable[LeagueStatFieldRow]
  def getScoringStatFieldsForPickee(leagueId: Long, pickeeId: Long)(implicit c: Connection): Iterable[LeagueStatFieldRow]
  def isStarted(league: LeagueRow): Boolean
  def insertStatField(leagueId: Long, name: String, description: Option[String])(implicit c: Connection): Long
  def insertLeaguePrize(leagueId: Long, description: String, email: String)(implicit c: Connection): Long
  def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long])(implicit c: Connection): Long
  def insertScoringField(statFieldId: Long, limitId: Option[Long], value: Double, noCardBonus: Boolean)(implicit c: Connection): Long
  def getPeriod(periodId: Long)(implicit c: Connection): Option[PeriodRow]
  def getPeriods(leagueId: Long)(implicit c: Connection): Iterable[PeriodRow]
  def getPeriodFromValue(leagueId: Long, value: Int)(implicit c: Connection): PeriodRow
  def getPeriodFromTimestamp(leagueId: Long, time: LocalDateTime)(implicit c: Connection): Option[PeriodRow]
  def getCurrentPeriod(league: LeagueRow)(implicit c: Connection): Option[PeriodRow]
  def getNextPeriod(league: LeagueRow, requireCurrentPeriodEnded: Boolean = false)(implicit c: Connection): Either[Result, PeriodRow]
  def detailedLeagueQueryExtractor(rows: Iterable[DetailedLeagueRow], scoringRules: Map[String, Map[String, Double]],
                                   showPeriods: Boolean, showScoring: Boolean, showStatfields: Boolean, showLimits: Boolean): LeagueFull // TODO private
  def updatePeriod(
                    leagueId: Long, periodValue: Int, start: Option[LocalDateTime], end: Option[LocalDateTime],
                    multiplier: Option[Double], onStartCloseTransferWindow: Option[Boolean],
                    onEndOpenTransferWindow: Option[Boolean], onEndEliminateUsersTo: Option[Int])(implicit c: Connection): Int
  def postStartPeriodHook(leagueId: Long, periodId: Long, periodValue: Int, timestamp: LocalDateTime)(
    implicit c: Connection
  )
  def postEndPeriodHook(leagueIds: Iterable[Long], periodIds: Iterable[Long], timestamp: LocalDateTime)(implicit c: Connection)
  def updateHistoricRanks(leagueId: Long)(implicit c: Connection)
  def eliminateUsersTo(leagueId: Long, eliminateTo: Int)(implicit c: Connection): Unit
  def startPeriods(currentTime: LocalDateTime)(implicit c: Connection)
  def endPeriods(currentTime: LocalDateTime)(implicit c: Connection)
  def insertLimits(leagueId: Long, limits: Iterable[LimitTypeInput])(implicit c: Connection): Map[String, Long]
  def getStatFieldId(leagueId: Long, statFieldName: String)(implicit c: Connection): Option[Long]
  def getStatFieldName(statFieldId: Long)(implicit c: Connection): Option[String]
  def getStatField(statFieldId: Long)(implicit c: Connection): Option[LeagueStatFieldRow]
  def getPointsForStat(statFieldId: Long, limitIds: Iterable[Long])(implicit c: Connection): Option[Double]
  def getScoringRules(leagueId: Long)(implicit c: Connection): Iterable[ScoringRow]
  def generateDraftOrder(leagueId: Long, maxPickees: Int)(implicit c: Connection): Iterable[Long]
  def setWaiverOrder(leagueId: Long, userIds: Iterable[Long~Long])(implicit c: Connection): Iterable[Long]
}

@Singleton
class LeagueRepoImpl @Inject()(implicit ec: LeagueExecutionContext) extends LeagueRepo{
  private val logger = Logger("application")
  private val leagueParser: RowParser[LeagueRow] = Macro.namedParser[LeagueRow](ColumnNaming.SnakeCase)
  private val detailedLeagueParser: RowParser[DetailedLeagueRow] = Macro.namedParser[DetailedLeagueRow](ColumnNaming.SnakeCase)

  override def get(leagueId: Long)(implicit c: Connection): Option[LeagueRow] = {
    SQL"""select l.league_id, league_name, api_key, game_id, is_private, tournament_id, pickee_description,
        period_description, transfer_limit, transfer_wildcard, starting_money, team_size, bench_size, transfer_open,
        force_full_teams, url, url_verified, current_period_id, apply_points_at_start_time,
         no_wildcard_for_late_register, system, recycle_value, pack_size, pack_cost, prediction_win_money, manually_calculate_points,
         draft_start, choice_timer, next_draft_deadline, manual_draft, paused as draft_paused
         from league l
         left join card_system using(league_id)
         left join transfer_system using(league_id)
         left join draft_system using(league_id)
         where league_id = $leagueId""".as(leagueParser.singleOpt)
  }


  override def getWithRelated(leagueId: Long, showPeriods: Boolean, showScoring: Boolean, showStatfields: Boolean, showLimits: Boolean)(implicit c: Connection): LeagueFull = {
    var extraJoins = ""
    var extraSelects = ""
    if (showPeriods) {
      extraJoins += " left join period p on (l.league_id = p.league_id) "
      extraSelects +=
        """, p.value as period_value, lower(p.timespan) as start, upper(p.timespan) as "end", p.multiplier,
        p.on_start_close_transfer_window, p.on_end_open_transfer_window, p.on_end_eliminate_users_to"""
    } else{
      extraSelects +=
        """,null as period_value, null as start, null as "end", null as multiplier,
          null as on_start_close_transfer_window, null as on_end_open_transfer_window, null as on_end_eliminate_users_to"""
    }
    if (showStatfields){
      extraJoins += " left join stat_field sf on(l.league_id = sf.league_id) "
      extraSelects += ",sf.name as stat_field_name, sf.description as stat_field_description"
    } else {
      extraSelects += ",null as stat_field_name, null as stat_field_description"
    }
    if (showLimits){
      extraJoins += """  left join limit_type lt on(l.league_id = lt.league_id)  left join "limit" lim using(limit_type_id) """
      extraSelects += """,lt.name as limit_type_name, lt.description, lim.name as limit_name, lim."max" as limit_max, lim.max_bench"""
    } else {
      extraSelects += """,null as limit_type_name, null as description, null as limit_name, null as limit_max, null as limit_max_bench"""
    }
    val sql = s"""select l.league_id, league_name, game_id, is_private, tournament_id, pickee_description, period_description,
          transfer_limit, transfer_wildcard, starting_money, team_size, bench_size, transfer_open, force_full_teams,
          url, url_verified, apply_points_at_start_time, no_wildcard_for_late_register, system, recycle_value,
          pack_size, pack_cost, prediction_win_money, (current_period_id is not null) as started,
          draft_start, choice_timer, next_draft_deadline, manual_draft, paused as draft_paused,
          (current_period_id is not null and upper(current_period.timespan) < now()) as ended,
          (select count(*) from period where league_id = $leagueId) as num_periods,
           current_period_id,
           current_period.value as current_period_value, lower(current_period.timespan) as current_period_start,
           upper(current_period.timespan) as current_period_end, current_period.multiplier as current_period_multiplier,
           current_period.on_start_close_transfer_window as current_period_on_start_close_transfer_window,
           current_period.on_end_open_transfer_window as current_period_on_end_open_transfer_window,
           current_period.on_end_eliminate_users_to as current_period_on_end_eliminate_users_to
          $extraSelects
          from league l
          left join card_system using(league_id)
          left join transfer_system using(league_id)
          left join draft_system using(league_id)
          left join period current_period on (current_period.league_id = l.league_id and current_period.period_id = l.current_period_id)
          $extraJoins where l.league_id = $leagueId"""
    val queryResult = SQL(sql).as(detailedLeagueParser.*)

//    val queryResult = SQL(s"""select l.league_id, league_name, game_id, is_private, tournament_id, pickee_description, period_description,
//          transfer_limit, transfer_wildcard, starting_money, team_size, transfer_open, force_full_teams,
//          url, url_verified, apply_points_at_start_time, no_wildcard_for_late_register, is_card_system, recycle_value,
//          pack_size, pack_cost, (current_period is not null) as started, (current_period is not null and upper(current_period.timespan) < now()) as ended,
//           p.value as period_value, lower(p.timespan) as start, upper(p.timespan) as "end", p.multiplier,
//           p.on_start_close_transfer_window, p.on_end_open_transfer_window,
//           sf.name as stat_field_name,
//           lt.name as limit_type_name, lt.description, lim.name as limit_name, lim."max" as limit_max
//          from league l
//          left join card_system using(league_id)
//          left join transfer_system using(league_id)
//    join period p using(league_id)
//    left join limit_type lt on(l.league_id = lt.league_id)
//    left join period current_period on (current_period.league_id = l.league_id and current_period.period_id = l.current_period_id)
//           left join "limit" lim using(limit_type_id)
//           join stat_field sf on(l.league_id = sf.league_id)
//        where l.league_id = $leagueId;""".stripMargin).as(detailedLeagueParser.*)
    val scoringRules = if (showScoring) ScoringRow.rowsToOut(getScoringRules(leagueId)) else Map[String, Map[String, Double]]()
    detailedLeagueQueryExtractor(queryResult, scoringRules, showPeriods, showScoring, showStatfields, showLimits)
        // deconstruct tuple
        // check what db queries would actuallly return
  }

  override def getStatFields(leagueId: Long)(implicit c: Connection): Iterable[LeagueStatFieldRow] = {
    val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
    val q = s"select stat_field_id, league_id, name, description from stat_field where league_id = $leagueId;"
    SQL(q).as(lsfParser.*)
  }

  override def getStatField(statFieldId: Long)(implicit c: Connection): Option[LeagueStatFieldRow] = {
    val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
    val q = s"select stat_field_id, league_id, name, description from stat_field where stat_field_id = $statFieldId;"
    SQL(q).as(lsfParser.singleOpt)
  }

  override def getScoringStatFieldsForPickee(leagueId: Long, pickeeId: Long)(implicit c: Connection): Iterable[LeagueStatFieldRow] = {
    // Because we dont want to give card bonus for stat fields which that pickee doesnt score on!!!
    logger.debug(s"getScoringStatFieldsForPickee: $pickeeId")
    val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
    // todo handle safely for if people do put 0.0 values in?
    SQL"""
           select stat_field_id, stat_field.league_id, stat_field.name, stat_field.description from stat_field join scoring s using(stat_field_id)
           left join "limit" lim using(limit_id)
           left join pickee_limit using(limit_id)
           left join pickee using(pickee_id)
           where (s.limit_id is null or (s.limit_id = lim.limit_id and pickee_id = $pickeeId)) and stat_field.league_id = $leagueId
           and NOT s.no_card_bonus
            """.as(lsfParser.*)
  }

  override def insert(input: LeagueFormInput)(implicit c: Connection): LeagueRow = {
    println("Inserting new league")
    val q = SQL(
      """insert into league(league_name, api_key, game_id, is_private, tournament_id, pickee_description, period_description,
        |starting_money, team_size, bench_size, force_full_teams, transfer_open,
        |url, url_verified, current_period_id, apply_points_at_start_time,
        |system, prediction_win_money) values ({name}, {apiKey}, {gameId}, {isPrivate}, {tournamentId},
        | {pickeeDescription}, {periodDescription},
        | {startingMoney}, {teamSize}, {benchSize}, {forceFullTeams}, false, {url}, false, null,
        |  {applyPointsAtStartTime}, {system}, {predictionWinMoney}) returning league_id;""".stripMargin
    ).on("name" -> input.name, "apiKey" -> input.apiKey, "gameId" -> input.gameId, "isPrivate" -> input.isPrivate,
      "tournamentId" -> input.tournamentId, "pickeeDescription" -> input.pickeeDescription,
      "periodDescription" -> input.periodDescription, "startingMoney" -> input.startingMoney,
      "teamSize" -> input.teamSize, "benchSize" -> input.benchSize, "forceFullTeams" -> input.transferInfo.forceFullTeams, "url" -> input.url.getOrElse(""),
      "applyPointsAtStartTime" -> input.applyPointsAtStartTime, "system" -> input.transferInfo.system,
      "predictionWinMoney" -> input.transferInfo.predictionWinMoney
    )
    println(q.sql)
    println(q)
    val newLeagueId: Option[Long]= q.executeInsert()
    if (input.transferInfo.system == "card"){
      SQL"""insert into card_system(league_id, recycle_value, pack_cost, pack_size) VALUES
            ($newLeagueId, ${input.transferInfo.recycleValue},
        ${input.transferInfo.cardPackCost}, ${input.transferInfo.cardPackSize})""".executeInsert()
    }
    else if (input.transferInfo.system == "draft"){
      val draftStart = input.transferInfo.draftStart.get
      val draftChoiceSecs = input.transferInfo.draftChoiceSecs.get
      val nextDraftDeadline = draftStart.plusSeconds(draftChoiceSecs)
      SQL"""insert into draft_system(league_id, draft_start, next_draft_deadline, choice_timer, is_snake) VALUES
            ($newLeagueId, $draftStart, $nextDraftDeadline,
        $draftChoiceSecs, true)""".executeInsert()
    }
    else {
      SQL"""insert into transfer_system(league_id, transfer_limit, transfer_wildcard, no_wildcard_for_late_register) VALUES
            ($newLeagueId, ${input.transferInfo.transferLimit},
        ${input.transferInfo.transferWildcard}, ${input.transferInfo.noWildcardForLateRegister})""".executeInsert()
    }
    // TODO maybe better do returning
    LeagueRow(newLeagueId.get, input.name, input.apiKey, input.gameId, input.isPrivate,
       input.tournamentId,  input.pickeeDescription,
      input.periodDescription, input.transferInfo.transferLimit,
      input.transferInfo.transferWildcard, input.startingMoney,
      input.teamSize, input.benchSize, false, input.transferInfo.forceFullTeams,
      input.url.getOrElse(""), false, null,
      input.applyPointsAtStartTime, input.transferInfo.noWildcardForLateRegister, input.manuallyCalculatePoints,
      input.transferInfo.system, input.transferInfo.recycleValue,
      input.transferInfo.cardPackCost, input.transferInfo.cardPackSize
    )
  }

  override def update(leagueId: Long, input: UpdateLeagueFormInput)(implicit c: Connection): LeagueRow = {
    logger.info(input.league.mkString(", "))
    logger.info(input.draft.mkString(", "))
    // TODO update update!!! hehe

    def dynamicUpdate(tableName: String, params: List[NamedParameter]) ={
      val updates = params.map(p => {
        val snakeName = camelToSnake(p.name)
        s"$snakeName = CASE WHEN {${p.name}} IS NULL THEN l.$snakeName ELSE {${p.name}} END"
      })
      val sql = s"""UPDATE $tableName l set ${updates.mkString(", ")} where league_id = $leagueId"""
      SQL(sql).on(params: _*).executeUpdate()
    }

    def hackDraftUpdate(input: UpdateLeagueFormDraftInput) ={
      input.draftStart.foreach(x =>
        SQL"""UPDATE draft_system set draft_start = $x where league_id = $leagueId""".executeUpdate()
      )
      input.nextDraftDeadline.foreach(x =>
        SQL"""UPDATE draft_system set next_draft_deadline = $x where league_id = $leagueId""".executeUpdate()
      )
      input.choiceTimer.foreach(x =>
        SQL"""UPDATE draft_system set choice_timer = $x where league_id = $leagueId""".executeUpdate())
      input.manualDraft.foreach(x =>
        SQL"""UPDATE draft_system set manual_draft = $x where league_id = $leagueId""".executeUpdate())

      input.paused.foreach(x => {
        SQL"""update draft_system set paused = $x where league_id = $leagueId""".executeUpdate()
        // TODO can be one update
        if (!x) {
          // If unpausing we need to reset deadline, as it will still have been ticking towards it when paused
          // (even though no actions will have been taken)
          SQL"""update draft_system set next_draft_deadline = now() + interval '1 second' * choice_timer where league_id = $leagueId""".executeUpdate()
        }
      })

      input.order.foreach(x => {
        SQL"""
             with internal_user_ids as (
              select user_id, ord from useru u
               JOIN unnest(array[$x]) WITH ORDINALITY t(id, ord) on
               (u.league_id = $leagueId AND t.id = u.external_user_id)
               ORDER by t.ord
             )
             insert into draft_order(league_id, user_ids) VALUES  ($leagueId,
              (select array_agg(user_id order by ord) from internal_user_ids))
             ON CONFLICT (league_id) DO
             update set user_ids = (select array_agg(user_id order by ord) from internal_user_ids)
              where EXCLUDED.league_id = $leagueId
          """.executeUpdate()
      })
    }

    input.league.map(x => dynamicUpdate("league", Macro.toParameters[UpdateLeagueFormBasicInput](x)))
    if (input.league.flatMap(_.url).isDefined){
      SQL"""UPDATE league set url_verified = false where league_id = $leagueId"""
    }
    input.transfer.map(x => dynamicUpdate("transfer_system", Macro.toParameters[UpdateLeagueFormTransferInput](x)))
    input.card.map(x => dynamicUpdate("card_system", Macro.toParameters[UpdateLeagueFormCardInput](x)))
    // input.draft.map(x => dynamicUpdate("draft_system", Macro.toParameters[UpdateLeagueFormDraftInput](x)))
    // why the fuck does this fail?
    input.draft.foreach(hackDraftUpdate)
    // TODO update transfer/card settings
    get(leagueId).get
  }

  override def isStarted(league: LeagueRow): Boolean = league.currentPeriodId.nonEmpty

  override def insertLeaguePrize(leagueId: Long, description: String, email: String)(implicit c: Connection): Long = {
    val q = "insert into league_prize(league_id, description, email) values ({leagueId}, {description}, {email}) returning league_prize_id;"
    SQL(q).on("leagueId" -> leagueId, "description" -> description, "email" -> email).executeInsert().get
  }

  override def insertStatField(leagueId: Long, name: String, description: Option[String])(implicit c: Connection): Long = {
    println("inserting stat field")
    val q = "insert into stat_field(league_id, name, description) values ({leagueId}, {name}, {description}) returning stat_field_id;"
    val out = SQL(q).on("leagueId" -> leagueId, "name" -> name, "description" -> description).executeInsert().get
    println("inserted stat field")
    out
  }

  override def insertScoringField(statFieldId: Long, limitId: Option[Long], value: Double, noCardBonus: Boolean)(implicit c: Connection): Long = {
    println("inserting scoring field")
    SQL("insert into scoring(stat_field_id, limit_id, value, no_card_bonus) VALUES ({statFieldId}, {limitId}, {value}, {noCardBonus});").on(
      "statFieldId" -> statFieldId, "limitId" -> limitId, "value" -> value, "noCardBonus" -> noCardBonus
    ).executeInsert().get
  }

  override def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long])(implicit c: Connection): Long = {
      SQL"""
        insert into period(league_id, value, timespan, multiplier, next_period_id, ended, on_start_close_transfer_window,
         on_end_open_transfer_window, on_end_eliminate_users_to) values (
        $leagueId, $period, tstzrange(${input.start}, ${input.end}), ${input.multiplier}, $nextPeriodId, false, ${input.onStartCloseTransferWindow},
         ${input.onEndOpenTransferWindow}, ${input.onEndEliminateUsersTo}
        ) returning period_id;""".executeInsert().get
  }

  override def getPeriod(periodId: Long)(implicit c: Connection): Option[PeriodRow] = {
    SQL"""select period_id, league_id, value, lower(timespan) as start, upper(timespan) as "end", multiplier,
        next_period_id, ended, on_start_close_transfer_window, on_end_open_transfer_window, on_end_eliminate_users_to
        from period
        where period_id = $periodId""".as(PeriodRow.parser.singleOpt)
  }

  override def getPeriods(leagueId: Long)(implicit c: Connection): Iterable[PeriodRow] = {
    SQL"""select period_id, league_id, value, lower(timespan) as start, upper(timespan) as "end", multiplier,
      next_period_id, ended, on_start_close_transfer_window, on_end_open_transfer_window, on_end_eliminate_users_to
      from period where league_id = $leagueId""".as(PeriodRow.parser.*)
  }

  override def getPeriodFromValue(leagueId: Long, value: Int)(implicit c: Connection): PeriodRow = {
    val q = s"""select period_id, league_id, value, lower(timespan) as start, upper(timespan) as "end", multiplier,
      next_period_id, ended, on_start_close_transfer_window, on_end_open_transfer_window, on_end_eliminate_users_to
       from period where league_id = $leagueId and value = $value;"""
    SQL(q).as(PeriodRow.parser.single)
  }

  override def getPeriodFromTimestamp(leagueId: Long, time: LocalDateTime)(implicit c: Connection): Option[PeriodRow] = {
    val q = """select period_id, league_id, value, lower(timespan) as start, upper(timespan) as "end", multiplier,
              next_period_id, ended, on_start_close_transfer_window, on_end_open_transfer_window, on_end_eliminate_users_to
               from period where league_id = {leagueId} and  timespan @> {time}::timestamptz;"""
    SQL(q).on("leagueId" -> leagueId, "time" -> time).as(PeriodRow.parser.singleOpt)
  }

  override def getCurrentPeriod(league: LeagueRow)(implicit c: Connection): Option[PeriodRow] = {
    val q = """select period_id, league_id, value, lower(timespan) as start, upper(timespan) as "end", multiplier,
      next_period_id, ended, on_start_close_transfer_window, on_end_open_transfer_window, on_end_eliminate_users_to
      from period where period_id = {periodId};"""
    SQL(q).on("periodId" -> league.currentPeriodId).as(PeriodRow.parser.singleOpt)
  }

  override def getNextPeriod(league: LeagueRow, requireCurrentPeriodEnded: Boolean = false)(implicit c: Connection): Either[Result, PeriodRow] = {
    // check if is above max?
    getCurrentPeriod(league) match {
      case Some(p) if requireCurrentPeriodEnded && !p.ended => Left(BadRequest("Must end current period before start next"))
      case Some(p) => {
        p.nextPeriodId match {
          case Some(np) => getPeriod(np).toRight(InternalServerError(s"Could not find next period $np, for period ${p.periodId}"))
          case None => Left(BadRequest("No more periods left to start. League is over"))
        }
      }
      case None => {
        Right(getPeriods(league.leagueId).toList.minBy(_.value))
      }
    }
  }

  override def detailedLeagueQueryExtractor(rows: Iterable[DetailedLeagueRow], scoringRules: Map[String, Map[String, Double]],
                                            showPeriods: Boolean, showScoring: Boolean, showStatfields: Boolean, showLimits: Boolean
                                           ): LeagueFull = {
    val league =  PublicLeagueRow.fromDetailedRow(rows.head)
    val statFields = if (showStatfields) rows.withFilter(_.statFieldName.isDefined).
      map(r => NameAndDescription(r.statFieldName.get, r.statFieldDescription)).toSet else Set[NameAndDescription]()
    //TODO sort
    val periods: Iterable[PeriodRow] = if (showPeriods) rows.groupBy(_.periodValue).map({ case (k, v) =>
      val r = v.head
      PeriodRow(-1, -1, r.periodValue.get, r.start.get, r.end.get, r.multiplier.get, r.onStartCloseTransferWindow.get,
        r.onEndOpenTransferWindow.get, onEndEliminateUsersTo=r.onEndEliminateUsersTo)
    }) else List()
    val currentPeriod = league.currentPeriod
    // TODO think this filter before group by inefficient
    val limits: Map[String, Iterable[LimitRow]] = if (showLimits) rows.filter(_.limitTypeName.isDefined).groupBy(_.limitTypeName.get).mapValues(
      v => v.groupBy(_.limitName.get).map({case(_, x2) => LimitRow(x2.head.limitName.get, x2.head.limitMax.get)})
    ) else Map()
    LeagueFull(league, limits, periods, currentPeriod, statFields, scoringRules)
  }

  override def updatePeriod(
                             leagueId: Long, periodValue: Int, start: Option[LocalDateTime], end: Option[LocalDateTime],
                             multiplier: Option[Double], onStartCloseTransferWindow: Option[Boolean],
                             onEndOpenTransferWindow: Option[Boolean], onEndEliminateUsersTo: Option[Int])(implicit c: Connection): Int = {
    var setString: String = ""
    var params: collection.mutable.Seq[NamedParameter] =
      collection.mutable.Seq(
        NamedParameter("value", periodValue),
        NamedParameter("league_id", leagueId))

    (start, end) match {
      case (Some(s), Some(e)) => {
        setString += ", timespan = tstzrange({start}, {end}"
        params = params :+ NamedParameter("start", s)
        params = params :+ NamedParameter("end", e)
      }
      case (Some(s), None) => {
        setString += ", timespan = tstzrange({start}, upper(timespan)"
        params = params :+ NamedParameter("start", s)
      }
      case (None, Some(e)) => {
        setString += ", timespan = tstzrange(lower(timespan), {end}"
        params = params :+ NamedParameter("end", e)
      }
      case _ => {}
    }
    if (multiplier.isDefined) {
      setString += ", [multiplier] = {multiplier}"
      params = params :+ NamedParameter("multiplier", multiplier.get)
    }

    if (onStartCloseTransferWindow.isDefined) {
      setString += ", on_start_close_transfer_window = {onStartCloseTransferWindow}"
      params = params :+ NamedParameter("onStartCloseTransferWindow", onStartCloseTransferWindow.get)
    }
    if (onEndOpenTransferWindow.isDefined) {
      setString += ", on_end_open_transfer_window = {onEndOpenTransferWindow}"
      params = params :+ NamedParameter("onEndOpenTransferWindow", onEndOpenTransferWindow.get)
    }
    // TODO need to handle setting it back to null
    if (onEndEliminateUsersTo.isDefined) {
      setString += ", on_end_eliminate_users_to = {onEndEliminateUsersTo}"
      params = params :+ NamedParameter("onEndEliminateUsersTo", onEndEliminateUsersTo.get)
    }
    SQL(
      "update period set " + setString + " WHERE [value] = {value} and [league_id] = {league_id}"
    ).on(params:_*).executeUpdate()
  }

  override def postEndPeriodHook(leagueIds: Iterable[Long], periodIds: Iterable[Long], timestamp: LocalDateTime)(implicit c: Connection): Unit = {
    println("tmp")
    // TODO batch
    println(s"""end period league: ${leagueIds.mkString(",")}, periodId: ${periodIds.mkString(",")}""")

    val parser = Macro.namedParser[PostPeriodHookInfo](ColumnNaming.SnakeCase)
    val hookInfo = periodIds.map(periodId => {
      val q =
        """update period set ended = true, timespan = tstzrange(lower(timespan), {timestamp})
    where period_id = {periodId} returning on_end_open_transfer_window, on_end_eliminate_users_to;
    """
      SQL(q).on("periodId" -> periodId, "timestamp" -> timestamp).as(
        parser.single)
    })
    leagueIds.zip(hookInfo).foreach({ case (lid, hookInfo) =>
      if (hookInfo.onEndOpenTransferWindow) {
        SQL"update league set transfer_open = true where league_id = $lid".executeUpdate()
      }
      if (hookInfo.onEndEliminateUsersTo.isDefined){
        eliminateUsersTo(lid, hookInfo.onEndEliminateUsersTo.get)
      }
    })
  }

  override def updateHistoricRanks(leagueId: Long)(implicit c: Connection): Unit = {
    SQL"""
       with rankings as (select user_stat_id, rank(PARTITION BY stat_field_id)
       OVER (order by value desc, useru.user_id) as ranking from useru
        join user_stat using(user_id)
         join user_stat_period using(user_stat_id)
          where league_id = $leagueId and period is null
           order by value desc)
           update user_stat us set previous_rank = rankings.ranking from rankings where rankings.user_stat_id = us.user_stat_id;
        """.executeUpdate()
  }

  override def eliminateUsersTo(leagueId: Long, eliminateTo: Int)(implicit c: Connection): Unit = {
    SQL"""
       with rankings as (select user_stat_id, rank(PARTITION BY stat_field_id)
       OVER (order by value desc, useru.user_id) as ranking from useru
        join user_stat using(user_id)
         join user_stat_period using(user_stat_id)
          where league_id = $leagueId and period is null
           order by value desc)
           update useru u set eliminated = true where ranking > $eliminateTo;
        """.executeUpdate()
  }

  override def postStartPeriodHook(leagueId: Long, periodId: Long, periodValue: Int, timestamp: LocalDateTime)(
    implicit c: Connection): Unit = {
    println("tmp")
    println(s"starting period league: $leagueId, periodId: $periodId, value: $periodValue")
    val closeTransfer = SQL("""update period set timespan = tstzrange({timestamp}, upper(timespan)) where period_id = {periodId} returning on_start_close_transfer_window;""").on(
      "timestamp" -> timestamp, "periodId" -> periodId
    ).as(SqlParser.bool("on_start_close_transfer_window").single)

    val transferOpenSet = if (closeTransfer) ", transfer_open = false" else ""
    SQL(
      s"update league set current_period_id = $periodId $transferOpenSet where league_id = $leagueId;"
    ).executeUpdate()
    if (periodValue > 1) updateHistoricRanks(leagueId)
  }

  override def endPeriods(currentTime: LocalDateTime)(implicit c: Connection) = {
    val q =
      """select l.league_id, period_id from league l join period p on (
        |l.current_period_id = p.period_id and p.ended = false and upper(p.timespan) <= {currentTime} and p.next_period_id is not null);""".stripMargin
    val (leagueIds, periodIds) = SQL(q).on("currentTime" -> currentTime).as(
      (SqlParser.long("league_id") ~ SqlParser.long("period_id")).*
    ).map(x => (x._1, x._2)).unzip
    postEndPeriodHook(leagueIds, periodIds, currentTime)
  }
  override def startPeriods(currentTime: LocalDateTime)(implicit c: Connection) = {
    // looking for period that a) isnt current period, b) isnt old ended period (so must be future period!)
    // and is future period that should have started...so lets start it
    val parser: RowParser[(Long, Long, Int)] = (
      SqlParser.long("league_id") ~
        SqlParser.long("period_id") ~
        SqlParser.int("value")
      ) map {
      case league_id ~ period_id ~ value => (league_id, period_id, value)
    }
    val q =
      """select l.league_id, period_id, value from league l join period p using(league_id)
        |where (l.current_period_id is null or l.current_period_id != p.period_id) and
        |p.ended = false and lower(p.timespan) <= {currentTime};""".stripMargin
    SQL(q).on("currentTime" -> currentTime).as(parser.*).foreach(
      x => postStartPeriodHook(x._1, x._2, x._3, currentTime)
    )
  }

  override def insertLimits(leagueId: Long, limits: Iterable[LimitTypeInput])(implicit c: Connection): Map[String, Long] = {
    // TODO bulk insert
    limits.toList.map(ft => {
      println(s"inserting limit type ${ft.name}: ${ft.max}")
      // = leagueRepo.insertLimits
      val sql = SQL(
        """
          |insert into limit_type(name, description, league_id, "max") values({name}, {description}, {leagueId}, {max})
          |returning limit_type_id""".stripMargin
      ).on(
        "leagueId" -> leagueId, "name" -> ft.name, "description" -> ft.description.getOrElse(ft.name), "max" -> ft.max
      )
      println(sql.sql)
      val newLimitTypeId: Long = sql.executeInsert().get
      ft.types.iterator.map(f => {
        println(s"inserting limit ${f.name}: ${f.max}")
        val newLimitId = SQL(
          """
            |insert into "limit"(limit_type_id, name, "max") values({factionTypeId}, {name}, {max}) returning limit_id""".stripMargin).on(
          "factionTypeId" -> newLimitTypeId, "name" -> f.name, "max" -> ft.max.getOrElse(f.max.get)
        ).executeInsert().get
        f.name -> newLimitId
      }).toMap
    }).reduceOption(_ ++ _).getOrElse(Map[String, Long]())
  }

  override def getStatFieldId(leagueId: Long, statFieldName: String)(implicit c: Connection): Option[Long] = {
    println(s"leagueid: $leagueId, statfieldname: $statFieldName")
    SQL(
      "select stat_field_id from stat_field where league_id = {leagueId} and name = {statFieldName}"
    ).on("leagueId" -> leagueId, "statFieldName" -> statFieldName).as(SqlParser.long("stat_field_id").singleOpt)
  }

  override def getStatFieldName(statFieldId: Long)(implicit c: Connection): Option[String] = {
    SQL(
      s"select name from stat_field where stat_field_id = $statFieldId"
    ).as(SqlParser.str("name").singleOpt)
  }

  override def getPointsForStat(statFieldId: Long, limitIds: Iterable[Long])(implicit c: Connection): Option[Double] = {
    // either this stat is faction agnostic, in which case there'll be a null entry
    // or we should find the faction entry in one of the limits
    // relies on only one limit type being used to determine points value
    SQL(
      """
        |select value from scoring where stat_field_id = {statFieldId} and (limit_id is null or limit_id in ({limitIds})) limit 1
        |""".stripMargin).on("statFieldId" -> statFieldId, "limitIds" -> limitIds.toList).as(
      SqlParser.double("value").singleOpt
    )
  }

  override def getScoringRules(leagueId: Long)(implicit c: Connection): Iterable[ScoringRow] = {
    SQL"""select stat_field.name as stat_field_name, l.name as limit_name, value as points from scoring s
          join stat_field using(stat_field_id)
          left join "limit" l using(limit_id)
          where league_id = $leagueId;""".as(ScoringRow.parser.*)
  }

  override def generateDraftOrder(leagueId: Long, maxPickees: Int)(implicit c: Connection): Iterable[Long] = {
    val randomUserIds = SQL"""select user_id, external_user_id from useru where league_id = $leagueId order by random()"""
      .as((SqlParser.long("user_id") ~ SqlParser.long("external_user_id")).*)
    val reversedUserIds = randomUserIds.reverse
    val draftOrder = (0 to maxPickees).map(i => {
      val userIds = if (i % 2 == 0) randomUserIds else reversedUserIds
      if (i == 1){
        setWaiverOrder(leagueId, userIds)  // TODO this shouldnt be confined to generateDraftOrder
      }
      userIds
    }).toList.flatten
    // Insert into db the internal ids
    SQL"""insert into draft_order(league_id, user_ids) values ($leagueId, ARRAY[${draftOrder.map(_._1)}])
        """.executeInsert()
    draftOrder.map(_._2)  // Output the external ids
  }

  override def setWaiverOrder(leagueId: Long, userIds: Iterable[Long~Long])(implicit c: Connection): Iterable[Long] = {
    // TODO can more efficient than map twice
    SQL"""insert into waiver_order(league_id, user_ids) values($leagueId, ARRAY[${userIds.map(_._1).toList}])""".executeInsert()
    userIds.map(_._2)
  }
}

