package v1.league

import java.sql.Connection

import javax.inject.{Inject, Singleton}
import java.time.LocalDateTime
//import java.math.BigDecimal
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, InternalServerError}
import anorm._
import anorm.~
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

import akka.actor.ActorSystem
import models._

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class LeagueFull(league: PublicLeagueRow, limits: Map[String, Iterable[LimitRow]], periods: Iterable[PeriodRow], currentPeriod: Option[PeriodRow], statFields: Iterable[String])

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
        "transferLimit" -> league.league.transferLimit, // use -1 for no transfer limit I think. only applies after period 1 start
        "transferWildcard" -> league.league.transferWildcard,
        "transferOpen" -> league.league.transferOpen,
        "transferDelayMinutes" -> league.league.transferDelayMinutes,
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
        "applyPointsAtStartTime" -> league.league.applyPointsAtStartTime,
        "url" -> {if (league.league.urlVerified) league.league.url else ""}
      )
    }
  }
}


trait LeagueRepo{
  def get(leagueId: Long)(implicit c: Connection): Option[LeagueRow]
  def getWithRelated(leagueId: Long)(implicit c: Connection): LeagueFull
  def insert(formInput: LeagueFormInput)(implicit c: Connection): LeagueRow
  def update(league: LeagueRow, input: UpdateLeagueFormInput)(implicit c: Connection): LeagueRow
  def getStatFields(leagueId: Long)(implicit c: Connection): Iterable[LeagueStatFieldRow]
  def isStarted(league: LeagueRow): Boolean
  def insertStatField(leagueId: Long, name: String)(implicit c: Connection): Long
  def insertLeaguePrize(leagueId: Long, description: String, email: String)(implicit c: Connection): Long
  def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long])(implicit c: Connection): Long
  def insertScoringField(statFieldId: Long, limitId: Option[Long], value: Double)(implicit c: Connection): Long
  def getPeriod(periodId: Long)(implicit c: Connection): Option[PeriodRow]
  def getPeriods(leagueId: Long)(implicit c: Connection): Iterable[PeriodRow]
  def getPeriodFromValue(leagueId: Long, value: Int)(implicit c: Connection): PeriodRow
  def getPeriodFromTimestamp(leagueId: Long, time: LocalDateTime)(implicit c: Connection): Option[PeriodRow]
  def getCurrentPeriod(league: LeagueRow)(implicit c: Connection): Option[PeriodRow]
  def getNextPeriod(league: LeagueRow)(implicit c: Connection): Either[Result, PeriodRow]
  def detailedLeagueQueryExtractor(rows: Iterable[DetailedLeagueRow]): LeagueFull // TODO private
  def updatePeriod(
                    leagueId: Long, periodValue: Int, start: Option[LocalDateTime], end: Option[LocalDateTime],
                    multiplier: Option[Double], onStartCloseTransferWindow: Option[Boolean],
                    onEndOpenTransferWindow: Option[Boolean])(implicit c: Connection): Int
  def postStartPeriodHook(leagueId: Long, periodId: Long, periodValue: Int, timestamp: LocalDateTime)(
    implicit c: Connection, updateHistoricRanks: Long => Unit
  )
  def postEndPeriodHook(leagueIds: Iterable[Long], periodIds: Iterable[Long], timestamp: LocalDateTime)(implicit c: Connection)
  def startPeriods(currentTime: LocalDateTime)(implicit c: Connection, updateHistoricRanksFunc: Long => Unit)
  def endPeriods(currentTime: LocalDateTime)(implicit c: Connection)
  def insertLimits(leagueId: Long, limits: Iterable[LimitTypeInput])(implicit c: Connection): Map[String, Long]
  def getStatFieldId(leagueId: Long, statFieldName: String)(implicit c: Connection): Option[Long]
  def getStatFieldName(statFieldId: Long)(implicit c: Connection): Option[String]
  def getPointsForStat(statFieldId: Long, limitIds: Iterable[Long])(implicit c: Connection): Double
}

@Singleton
class LeagueRepoImpl @Inject()(implicit ec: LeagueExecutionContext) extends LeagueRepo{

  private val leagueParser: RowParser[LeagueRow] = Macro.namedParser[LeagueRow](ColumnNaming.SnakeCase)
  private val detailedLeagueParser: RowParser[DetailedLeagueRow] = Macro.namedParser[DetailedLeagueRow](ColumnNaming.SnakeCase)

  override def get(leagueId: Long)(implicit c: Connection): Option[LeagueRow] = {
    SQL(
      s"""select league_id, league_name, api_key, game_id, is_private, tournament_id, pickee_description,
        |period_description, transfer_limit, transfer_wildcard, starting_money, team_size, transfer_delay_minutes, transfer_open,
        |force_full_teams, url, url_verified, current_period_id, apply_points_at_start_time,
        | no_wildcard_for_late_register, manually_apply_points
        | from league where league_id = $leagueId;""".stripMargin).as(leagueParser.singleOpt)
  }


  override def getWithRelated(leagueId: Long)(implicit c: Connection): LeagueFull = {
    val queryResult = SQL(s"""select l.league_id, league_name, game_id, is_private, tournament_id, pickee_description, period_description,
          transfer_limit, transfer_wildcard, starting_money, team_size, transfer_delay_minutes, transfer_open, force_full_teams,
          url, url_verified, apply_points_at_start_time, no_wildcard_for_late_register,
           (current_period is not null) as started, (current_period is not null and upper(current_period.timespan) < now()) as ended,
           p.value as period_value, lower(p.timespan) as start, upper(p.timespan) as "end", p.multiplier,
           p.on_start_close_transfer_window, p.on_end_open_transfer_window,
          CASE WHEN l.current_period_id is null then false
          WHEN p.period_id = l.current_period_id then true
           ELSE false END as current, sf.name as stat_field_name,
           lt.name as limit_type_name, lt.description, lim.name as limit_name, lim."max" as limit_max
          from league l
    join period p using(league_id)
    left join limit_type lt on(l.league_id = lt.league_id)
    left join period current_period on (current_period.league_id = l.league_id and current_period.period_id = l.current_period_id)
           left join "limit" lim using(limit_type_id)
           join stat_field sf on(l.league_id = sf.league_id)
        where l.league_id = $leagueId order by p.value;""".stripMargin).as(detailedLeagueParser.*)
    detailedLeagueQueryExtractor(queryResult)
        // deconstruct tuple
        // check what db queries would actuallly return
  }

  override def getStatFields(leagueId: Long)(implicit c: Connection): Iterable[LeagueStatFieldRow] = {
    val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
    val q = s"select stat_field_id, league_id, name from stat_field where league_id = $leagueId;"
    SQL(q).as(lsfParser.*)
  }

  override def insert(input: LeagueFormInput)(implicit c: Connection): LeagueRow = {
    println("Inserting new league")
    val q = SQL(
      """insert into league(league_name, api_key, game_id, is_private, tournament_id, pickee_description, period_description, transfer_limit,
        |transfer_wildcard, starting_money, team_size, force_full_teams, transfer_open,
        |transfer_delay_minutes, url, url_verified, current_period_id, apply_points_at_start_time,
        |no_wildcard_for_late_register) values ({name}, {apiKey}, {gameId}, {isPrivate}, {tournamentId},
        | {pickeeDescription}, {periodDescription}, {transferLimit}, {transferWildcard},
        | {startingMoney}, {teamSize}, {forceFullTeams}, false, {transferDelayMinutes}, {url}, false, null,
        |  {applyPointsAtStartTime},
        | {noWildcardForLateRegister}) returning league_id;""".stripMargin
    ).on("name" -> input.name, "apiKey" -> input.apiKey, "gameId" -> input.gameId, "isPrivate" -> input.isPrivate,
      "tournamentId" -> input.tournamentId, "pickeeDescription" -> input.pickeeDescription,
      "periodDescription" -> input.periodDescription, "transferLimit" -> input.transferInfo.transferLimit,
      "transferWildcard" -> input.transferInfo.transferWildcard, "startingMoney" -> input.startingMoney,
      "teamSize" -> input.teamSize, "forceFullTeams" -> input.transferInfo.forceFullTeams,
      "transferDelayMinutes" -> input.transferInfo.transferDelayMinutes, "url" -> input.url.getOrElse(""),
      "applyPointsAtStartTime" -> input.applyPointsAtStartTime,
      "noWildcardForLateRegister" -> input.transferInfo.noWildcardForLateRegister)
    println(q.sql)
    println(q)
    val newLeagueId: Option[Long]= q.executeInsert()
    println(newLeagueId)
    // TODO maybe better do returning
    LeagueRow(newLeagueId.get, input.name, input.apiKey, input.gameId, input.isPrivate,
       input.tournamentId,  input.pickeeDescription,
      input.periodDescription, input.transferInfo.transferLimit,
      input.transferInfo.transferWildcard, input.startingMoney,
      input.teamSize, input.transferInfo.transferDelayMinutes, false, input.transferInfo.forceFullTeams,
      input.url.getOrElse(""), false, null,
      input.applyPointsAtStartTime, input.transferInfo.noWildcardForLateRegister)
  }

  override def update(league: LeagueRow, input: UpdateLeagueFormInput)(implicit c: Connection): LeagueRow = {
    // TODO update update!!! hehe
    var setString: String = ""
    var params: collection.mutable.Seq[NamedParameter] =
      collection.mutable.Seq(NamedParameter("leagueId", league.leagueId))
    if (input.name.isDefined) {
      setString += ", league_name = '{leagueName}'"
      params = params :+ NamedParameter("leagueName", input.name.get)
    }
    if (input.isPrivate.isDefined) {
      setString += ", is_private = {isPrivate}"
      params = params :+ NamedParameter("isPrivate", input.isPrivate.get)
    }
    if (input.transferOpen.isDefined) {
      setString += ", transfer_open = {transferOpen}"
      params = params :+ NamedParameter("transferOpen", input.transferOpen.get)
    }
    if (input.forceFullTeams.isDefined) {
      setString += ", force_full_teams = {forceFullTeams}"
      params = params :+ NamedParameter("forceFullTeams", input.forceFullTeams.get)
    }
    if (input.transferDelayMinutes.isDefined) {
      setString += ", transfer_delay_minutes = {transferDelayMinutes}"
      params = params :+ NamedParameter("transferDelayMinutes", input.transferDelayMinutes.get)
    }
    if (input.periodDescription.isDefined) {
      setString += ", period_description = '{periodDescription}'"
      params = params :+ NamedParameter("periodDescription", input.periodDescription.get)
    }
    if (input.pickeeDescription.isDefined) {
      setString += ", pickee_description = '{pickeeDescription}'"
      params = params :+ NamedParameter("pickeeDescription", input.pickeeDescription.get)
    }
    if (input.transferLimit.isDefined) {
      setString += ", transfer_limit = {transferLimit}"
      params = params :+ NamedParameter("transferLimit", input.transferLimit.get)
    }
    if (input.transferWildcard.isDefined) {
      setString += ", transfer_wildcard = {transferWildcard}"
      params = params :+ NamedParameter("transferWildcard", input.transferWildcard.get)
    }
    if (input.applyPointsAtStartTime.isDefined) {
      setString += ", apply_points_at_start_time = {applyPointsAtStartTime}"
      params = params :+ NamedParameter("applyPointsAtStartTime", input.applyPointsAtStartTime.get)
    }
    if (input.url.isDefined) {
      setString += ", url = {url}"
      params = params :+ NamedParameter("url", input.url.get)
      setString += ", url_verified = false"
    }
    setString = setString.tail  // remove starting comma
    SQL(
      "update league set " + setString + " WHERE league_id = {leagueId}"
    ).on(params:_*).executeUpdate()
    // TODO returning, or overwrite league row
    get(league.leagueId).get
  }

  override def isStarted(league: LeagueRow): Boolean = league.currentPeriodId.nonEmpty

  override def insertLeaguePrize(leagueId: Long, description: String, email: String)(implicit c: Connection): Long = {
    val q = "insert into league_prize(league_id, description, email) values ({leagueId}, {description}, {email}) returning league_prize_id;"
    SQL(q).on("leagueId" -> leagueId, "description" -> description, "email" -> email).executeInsert().get
  }

  override def insertStatField(leagueId: Long, name: String)(implicit c: Connection): Long = {
    println("inserting stat field")
    val q = "insert into stat_field(league_id, name) values ({leagueId}, {name}) returning stat_field_id;"
    val out = SQL(q).on("leagueId" -> leagueId, "name" -> name).executeInsert().get
    println("inserted stat field")
    out
  }

  override def insertScoringField(statFieldId: Long, limitId: Option[Long], value: Double)(implicit c: Connection): Long = {
    println("inserting scoring field")
    SQL("insert into scoring(stat_field_id, limit_id, value) VALUES ({statFieldId}, {limitId}, {value});").on(
      "statFieldId" -> statFieldId, "limitId" -> limitId, "value" -> value
    ).executeInsert().get
  }

  override def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long])(implicit c: Connection): Long = {
    val q =
      s"""insert into period(league_id, value, timespan, multiplier, next_period_id, ended, on_start_close_transfer_window,
         | on_end_open_transfer_window) values (
        |{leagueId}, {period}, tstzrange({start}, {end}), {multiplier}, {nextPeriodId}, false, {onStartCloseTransferWindow},
         | {onEndOpenTransferWindow}
        |) returning period_id;""".stripMargin
    SQL(q).on("leagueId" -> leagueId, "nextPeriodId" -> nextPeriodId, "period" -> period, "start" -> input.start,
    "end" -> input.end, "multiplier" -> input.multiplier, "onStartCloseTransferWindow" -> input.onStartCloseTransferWindow,
    "onEndOpenTransferWindow" -> input.onEndOpenTransferWindow).executeInsert().get
  }

  override def getPeriod(periodId: Long)(implicit c: Connection): Option[PeriodRow] = {
    val q =
      """select period_id, league_id, value, lower(timespan) as start, upper(timespan) as "end", multiplier,
        | next_period_id, ended, on_start_close_transfer_window, on_end_open_transfer_window
        | from period
        | where period_id = {periodId};""".stripMargin
    SQL(q).on("periodId" -> periodId).as(PeriodRow.parser.singleOpt)
  }

  override def getPeriods(leagueId: Long)(implicit c: Connection): Iterable[PeriodRow] = {
    val q = s"""select period_id, league_id, value, lower(timespan) as start, upper(timespan) as "end", multiplier,
      next_period_id, ended, on_start_close_transfer_window, on_end_open_transfer_window
      from period where league_id = $leagueId;"""
    SQL(q).as(PeriodRow.parser.*)
  }

  override def getPeriodFromValue(leagueId: Long, value: Int)(implicit c: Connection): PeriodRow = {
    val q = s"""select period_id, league_id, value, lower(timespan) as start, upper(timespan) as "end", multiplier,
      next_period_id, ended, on_start_close_transfer_window, on_end_open_transfer_window
       from period where league_id = $leagueId and value = $value;"""
    SQL(q).as(PeriodRow.parser.single)
  }

  override def getPeriodFromTimestamp(leagueId: Long, time: LocalDateTime)(implicit c: Connection): Option[PeriodRow] = {
    val q = """select period_id, league_id, value, lower(timespan) as start, upper(timespan) as "end", multiplier,
              next_period_id, ended, on_start_close_transfer_window, on_end_open_transfer_window
               from period where league_id = {leagueId} and  timespan @> {time}::timestamptz;"""
    SQL(q).on("leagueId" -> leagueId, "time" -> time).as(PeriodRow.parser.singleOpt)
  }

  override def getCurrentPeriod(league: LeagueRow)(implicit c: Connection): Option[PeriodRow] = {
    val q = """select period_id, league_id, value, lower(timespan) as start, upper(timespan) as "end", multiplier,
      next_period_id, ended, on_start_close_transfer_window, on_end_open_transfer_window
      from period where period_id = {periodId};"""
    SQL(q).on("periodId" -> league.currentPeriodId).as(PeriodRow.parser.singleOpt)
  }

  override def getNextPeriod(league: LeagueRow)(implicit c: Connection): Either[Result, PeriodRow] = {
    // check if is above max?
    getCurrentPeriod(league) match {
      case Some(p) if !p.ended => Left(BadRequest("Must end current period before start next"))
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

  override def detailedLeagueQueryExtractor(rows: Iterable[DetailedLeagueRow]): LeagueFull = {
    val league = PublicLeagueRow.fromDetailedRow(rows.head)
    val statFields = rows.flatMap(_.statFieldName)
    val periods = rows.map(
      r => PeriodRow(-1, -1, r.periodValue, r.start, r.end, r.multiplier, r.onStartCloseTransferWindow, r.onEndOpenTransferWindow)
    )
    val currentPeriod = rows.withFilter(_.current).map(r => PeriodRow(
      -1, -1, r.periodValue, r.start, r.end, r.multiplier, r.onStartCloseTransferWindow, r.onEndOpenTransferWindow
    )).headOption
    // TODO think this filter before group by inefficient
    val limits: Map[String, Iterable[LimitRow]] = rows.filter(_.limitTypeName.isDefined).groupBy(_.limitTypeName.get).mapValues(
      v => v.map(x => LimitRow(x.limitName.get, x.limitMax.get))
    )
    LeagueFull(league, limits, periods, currentPeriod, statFields)
  }

  override def updatePeriod(
                             leagueId: Long, periodValue: Int, start: Option[LocalDateTime], end: Option[LocalDateTime],
                             multiplier: Option[Double], onStartCloseTransferWindow: Option[Boolean],
                             onEndOpenTransferWindow: Option[Boolean])(implicit c: Connection): Int = {
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
    SQL(
      "update period set " + setString + " WHERE [value] = {value} and [league_id] = {league_id}"
    ).on(params:_*).executeUpdate()
  }

  override def postEndPeriodHook(leagueIds: Iterable[Long], periodIds: Iterable[Long], timestamp: LocalDateTime)(implicit c: Connection): Unit = {
    println("tmp")
    // TODO batch
    println(s"""end period league: ${leagueIds.mkString(",")}, periodId: ${periodIds.mkString(",")}""")
    val open_transfers = periodIds.map(periodId => {
      val q =
        """update period set ended = true, timespan = tstzrange(lower(timespan), {timestamp})
    where period_id = {periodId} returning on_end_open_transfer_window;
    """
      SQL(q).on("periodId" -> periodId, "timestamp" -> timestamp).as(
        SqlParser.bool("on_end_open_transfer_window").single)
    })
    leagueIds.zip(open_transfers).withFilter(_._2).foreach({ case (lid, _) =>
      SQL(
        s"update league set transfer_open = true where league_id = $lid;"
      ).executeUpdate()
    })
  }

  override def postStartPeriodHook(leagueId: Long, periodId: Long, periodValue: Int, timestamp: LocalDateTime)(
    implicit c: Connection, updateHistoricRanks: Long => Unit): Unit = {
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
  override def startPeriods(currentTime: LocalDateTime)(
    implicit c: Connection, updateHistoricRanksFunc: Long => Unit
  ) = {
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
      // = leagueRepo.insertLimits
      val newLimitTypeId: Long = SQL(
        """insert into limit_type(league_id, name, description, "max") values({leagueId}, {name}, {description}, {max});""").on(
        "leagueId" -> leagueId, "name" -> ft.name, "description" -> ft.description.getOrElse(ft.name), "max" -> ft.max
      ).executeInsert().get
      ft.types.iterator.map(f => {
        val newLimitId = SQL("""insert into "limit"(faction_type_id, name, "max") values({factionTypeId}, {name}, {max});""").on(
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

  override def getPointsForStat(statFieldId: Long, limitIds: Iterable[Long])(implicit c: Connection): Double = {
    // either this stat is faction agnostic, in which case there'll be a null entry
    // or we should find the faction entry in one of the limits
    // relies on only one limit type being used to determine points value
    SQL(
      """
        |select value from scoring where stat_field_id = {statFieldId} and (limit_id is null or limit_id in ({limitIds}) limit 1
        |""".stripMargin).on("statFieldId" -> statFieldId, "limitIds" -> limitIds.toList).as(
      SqlParser.double("value").single
    )
  }
}

