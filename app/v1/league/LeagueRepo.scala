package v1.league

import java.sql.Connection
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, InternalServerError}
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import java.time.LocalDateTime

import anorm._
import anorm.SqlParser.long
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

import models.AppDB._
import models._
import v1.leagueuser.LeagueUserRepo

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class LeagueFull(league: League, limits: Iterable[LimitTypeOut], periods: Iterable[Period], currentPeriod: Option[Period], statFields: Iterable[LeagueStatField])

case class PeriodAndLeagueRow(leagueId: Long, periodId: Long)

object LeagueFull{
  implicit val implicitWrites = new Writes[LeagueFull] {
    def writes(league: LeagueFull): JsValue = {
      Json.obj(
        "id" -> league.league.id,
        "name" -> league.league.name,
        "gameId" -> league.league.gameId,
        "tournamentId" -> league.league.tournamentId,
        "isPrivate" -> league.league.isPrivate,
        "tournamentId" -> league.league.tournamentId,
        "pickee" -> league.league.pickeeDescription,
        "teamSize" -> league.league.teamSize,
        "transferLimit" -> league.league.transferLimit, // use -1 for no transfer limit I think. only applies after period 1 start
        "transferWildcard" -> league.league.transferWildcard,
        "transferOpen" -> league.league.transferOpen,
        "transferDelayMinutes" -> league.league.transferDelayMinutes,
        "transferBlockedDuringPeriod" -> league.league.transferBlockedDuringPeriod,
        "startingMoney" -> league.league.startingMoney,
        "statFields" -> league.statFields.map(_.name),
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

case class LeagueFullQuery(league: League, period: Option[Period], limitType: Option[LimitType], limit: Option[Limit], statField: Option[LeagueStatField])


trait LeagueRepo{
  def get(id: Long): Option[League]
  def get2(id: Long)(implicit c: Connection): Option[LeagueRow]
  def getWithRelated(id: Long): LeagueFull
  def insert(formInput: LeagueFormInput)(implicit c: Connection): LeagueRow
  def update(league: LeagueRow, input: UpdateLeagueFormInput)(implicit c: Connection): LeagueRow
  def getStatFields(league: LeagueRow)(implicit c: Connection): Iterable[LeagueStatFieldRow]
  def getStatFieldNames(statFields: Iterable[LeagueStatField]): Array[String]
  def isStarted(league: LeagueRow): Boolean
  def insertLeagueStatField(leagueId: Long, name: String): LeagueStatField
  def insertLeaguePrize(leagueId: Long, description: String, email: String): LeaguePrize
  def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long]): Period
  def getPeriod(periodId: Long)(implicit c: Connection): Option[PeriodRow]
  def getPeriods(league: LeagueRow)(implicit c: Connection): Iterable[PeriodRow]
  def getPeriodBetween(leagueId: Long, time: LocalDateTime)(implicit c: Connection): Option[PeriodRow]
  def getCurrentPeriod(league: LeagueRow)(implicit c: Connection): Option[PeriodRow]
  def getNextPeriod(league: LeagueRow)(implicit c: Connection): Either[Result, PeriodRow]
  def leagueFullQueryExtractor(q: Iterable[LeagueFullQuery]): LeagueFull
  def updatePeriod(
                    leagueId: Long, periodValue: Int, start: Option[LocalDateTime], end: Option[LocalDateTime],
                    multiplier: Option[Double])(implicit c: Connection): Period
  def postStartPeriodHook(league: LeagueRow, period: PeriodRow, timestamp: LocalDateTime)(implicit c: Connection)
  def postEndPeriodHook(periodIds: Iterable[Long], leagueIds: Iterable[Long], timestamp: LocalDateTime)(implicit c: Connection)
  def startPeriods(currentTime: LocalDateTime)(implicit c: Connection)
  def endPeriods(currentTime: LocalDateTime)(implicit c: Connection)
  def getLimitTypes(leagueId: Long)(implicit c: Connection): Iterable[LimitType]
}

@Singleton
class LeagueRepoImpl @Inject()(implicit ec: LeagueExecutionContext) extends LeagueRepo{

  private val periodParser: RowParser[PeriodRow] = Macro.namedParser[PeriodRow](ColumnNaming.SnakeCase)
  private val leagueParser: RowParser[LeagueRow] = Macro.namedParser[LeagueRow](ColumnNaming.SnakeCase)
  override def get(id: Long): Option[League] = {
    leagueTable.lookup(id)
  }

  override def get2(id: Long)(implicit c: Connection): Option[LeagueRow] = {
    SQL("select * from league where league_id = {id}").on("id" -> id).as(leagueParser.singleOpt)
  }

  override def getWithRelated(id: Long): LeagueFull = {
    val queryResult = join(leagueTable, periodTable.leftOuter, limitTypeTable.leftOuter, limitTable.leftOuter, leagueStatFieldTable.leftOuter)((l, p, ft, f, s) =>
        where(l.id === id)
        select((l, p, ft, f, s))
        on(l.id === p.map(_.leagueId), l.id === ft.map(_.leagueId), f.map(_.limitTypeId) === ft.map(_.id), s.map(_.leagueId) === l.id)
        ).map(LeagueFullQuery.tupled(_))
    leagueFullQueryExtractor(queryResult)
        // deconstruct tuple
        // check what db queries would actuallly return
  }

  override def getStatFields(league: LeagueRow)(implicit c: Connection): Iterable[LeagueStatFieldRow] = {
    val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
    val q = "select * from league_stat_field where league_id = {leagueId};"
    SQL(q).on("leagueId" -> league.id).as(lsfParser.*)
  }

  override def getStatFieldNames(statFields: Iterable[LeagueStatField]): Array[String] = {
    statFields.map(_.name).toArray
  }

  override def insert(input: LeagueFormInput)(implicit c: Connection): LeagueRow = {
    println("Inserting new league")
    val newTeamId: Option[Long] = SQL(
      """insert into league(name, api_key, game_id, is_private, tournament_id, pickee_description, period_description, transfer_limit
        |transfer_wildcard, starting_money, team_size, transfer_blocked_during_period, transfer_open,
        |transfer_delay_minutes, url, url_verified, current_period_id, apply_points_at_start_time
        |no_wildcard_for_late_register) values ({name}, {apiKey}, {gameId}, {isPrivate}, {tournamentId},
        | {pickeeDescription}, {periodDescription}, {transferInfo.transferLimit}, {transferInfo.transferWildcard},
        | {startingMoney}, {teamSize}, {transferBlockedDuringPeriod}, false, {transferDelayMinutes}, {url}, false, null, {applyPointsAtStartTime},
        | {noWildcardForLateRegister});""".stripMargin
    ).on("name" -> input.name, "apiKey" -> input.apiKey, "gameId" -> input.gameId, "isPrivate" -> input.isPrivate,
      "tournamentId" -> input.tournamentId, "pickeeDescription" -> input.pickeeDescription,
      "periodDescription" -> input.periodDescription, "transferLimit" -> input.transferInfo.transferLimit,
      "transferWildcard" -> input.transferInfo.transferWildcard, "startingMoney" -> input.startingMoney,
      "teamSize" -> input.teamSize, "transferBlockedDuringPeriod" -> input.transferInfo.transferBlockedDuringPeriod,
      "transferDelayMinutes" -> input.transferInfo.transferDelayMinutes, "url" -> input.url.getOrElse(""),
      "applyPointsAtStartTime" -> input.applyPointsAtStartTime,
      "noWildcardForLateRegister" -> input.transferInfo.noWildcardForLateRegister).executeInsert()

    LeagueRow(newTeamId.get, input.name, input.apiKey, input.gameId, input.isPrivate,
       input.tournamentId,  input.pickeeDescription,
      input.periodDescription, input.transferInfo.transferLimit,
      input.transferInfo.transferWildcard, input.startingMoney,
      input.teamSize, input.transferInfo.transferDelayMinutes, false, input.transferInfo.transferBlockedDuringPeriod,
      input.url.getOrElse(""), false, null,
      input.applyPointsAtStartTime, input.transferInfo.noWildcardForLateRegister)
  }

  override def update(league: LeagueRow, input: UpdateLeagueFormInput)(implicit c: Connection): LeagueRow = {
    // TODO update update!!! hehe
    league
//    league.name = input.name.getOrElse(league.name)
//    league.isPrivate = input.isPrivate.getOrElse(league.isPrivate)
//    league.transferOpen = input.transferOpen.getOrElse(league.transferOpen)
//    league.transferBlockedDuringPeriod = input.transferBlockedDuringPeriod.getOrElse(league.transferBlockedDuringPeriod)
//    league.transferDelayMinutes = input.transferDelayMinutes.getOrElse(league.transferDelayMinutes)
//    println(league.transferDelayMinutes)
//    league.periodDescription = input.periodDescription.getOrElse(league.periodDescription)
//    league.pickeeDescription = input.pickeeDescription.getOrElse(league.pickeeDescription)
//    league.transferLimit = if (input.transferLimit.nonEmpty) input.transferLimit else league.transferLimit
//    league.transferWildcard = input.transferWildcard.getOrElse(league.transferWildcard)
//    league.noWildcardForLateRegister = input.noWildcardForLateRegister.getOrElse(league.noWildcardForLateRegister)
//    league.applyPointsAtStartTime = input.applyPointsAtStartTime.getOrElse(league.applyPointsAtStartTime)
//    input.url.foreach(u => {
//      league.url = u
//      league.urlVerified = false
//    })
//    leagueTable.update(league)
//    league
  }

  override def isStarted(league: LeagueRow): Boolean = league.currentPeriodId.nonEmpty

  override def insertLeaguePrize(leagueId: Long, description: String, email: String): LeaguePrize = {
    leaguePrizeTable.insert(new LeaguePrize(leagueId, description, email))
  }

  override def insertLeagueStatField(leagueId: Long, name: String): LeagueStatField = {
    leagueStatFieldTable.insert(new LeagueStatField(leagueId, name))
  }

  override def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long]): Period = {
    periodTable.insert(new Period(leagueId, period, input.start, input.end, input.multiplier, nextPeriodId))
  }

  override def getPeriod(periodId: Long)(implicit c: Connection): Option[PeriodRow] = {
    val q = "select * from period where period_id = {periodId};"
    SQL(q).on("periodId" -> periodId).as(periodParser.singleOpt)
  }

  override def getPeriods(league: LeagueRow)(implicit c: Connection): Iterable[PeriodRow] = {
    val q = "select * from period where league_id = {leagueId};"
    SQL(q).on("leagueId" -> league.id).as(periodParser.*)
  }

  override def getPeriodBetween(leagueId: Long, time: LocalDateTime)(implicit c: Connection): Option[PeriodRow] = {
    val q = """select * from period where league_id = {leagueId} and start <= {time} and "end" > {time};"""
    SQL(q).on("time" -> time).as(periodParser.singleOpt)
  }

  override def getCurrentPeriod(league: LeagueRow)(implicit c: Connection): Option[PeriodRow] = {
    val q = "select * from period where period_id = {periodId};"
    SQL(q).on("periodId" -> league.currentPeriodId).as(periodParser.singleOpt)
  }

  override def getNextPeriod(league: LeagueRow)(implicit c: Connection): Either[Result, PeriodRow] = {
    // check if is above max?
    getCurrentPeriod(league) match {
      case Some(p) if !p.ended => Left(BadRequest("Must end current period before start next"))
      case Some(p) => {
        p.nextPeriodId match {
          case Some(np) => getPeriod(np).toRight(InternalServerError(s"Could not find next period $np, for period ${p.id}"))
          case None => Left(BadRequest("No more periods left to start. League is over"))
        }
      }
      case None => {
        // TODO sort by value
        Right(getPeriods(league).toList.sortBy(_.value).head)
      }
    }
  }

  override def leagueFullQueryExtractor(q: Iterable[LeagueFullQuery]): LeagueFull = {
    val league = q.toList.head.league
    val periods = q.flatMap(_.period).toSet
    println(periods)
    val currentPeriod = periods.find(p => league.currentPeriodId.contains(p.id))
    val statFields = q.flatMap(_.statField).toSet
    val limits = q.map(f => (f.limitType, f.limit)).filter(_._2.isDefined).map(f => (f._1.get, f._2.get)).groupBy(_._1).mapValues(_.map(_._2).toSet)
    // keep limits as well
    val limitsOut = limits.map({case (k, v) => LimitTypeOut(k.name, k.description, v)})
    LeagueFull(league, limitsOut, periods, currentPeriod, statFields)
  }

  override def updatePeriod(
                             leagueId: Long, periodValue: Int, start: Option[LocalDateTime], end: Option[LocalDateTime],
                             multiplier: Option[Double])(implicit c: Connection): Period = {
    val period = from(periodTable)(p => 
        where(p.leagueId === leagueId and p.value === periodValue)
        select(p)
      ).single
    period.start = start.getOrElse(period.start)
    period.end = end.getOrElse(period.end)
    period.multiplier = multiplier.getOrElse(period.multiplier)
    periodTable.update(period)
    period
  }

  override def postEndPeriodHook(periodIds: Iterable[Long], leagueIds: Iterable[Long], timestamp: LocalDateTime)(implicit c: Connection) = {
    println("tmp")
    // TODO batch
    periodIds.foreach(periodId => {
      val q =
        """update period set ended = true and "end" = {timestamp}
    where period_id = {periodId};
    """
      SQL(q).on("periodId" -> periodId).executeUpdate()
    })
//    period.ended = true
//    period.end = timestamp
//    periodTable.update(period)
    // TODO HASHDSAHD
//    league.transferOpen = true
//    leagueTable.update(league)
  }

  override def postStartPeriodHook(league: LeagueRow, period: PeriodRow, timestamp: LocalDateTime)(implicit c: Connection) = {
    println("tmp")
//    period.start = timestamp
//    periodTable.update(period)
//    league.currentPeriodId = Some(period.id)
//    if (league.transferBlockedDuringPeriod) {
//      league.transferOpen = false
//    }
//    leagueTable.update(league)
//    if (period.value > 1) updateHistoricRanks(league)
  }

  override def endPeriods(currentTime: LocalDateTime)(implicit c: Connection) = {
    val q =
      """select league_id, period_id from league l join period p on (
        |l.current_period_id = p.period_id and p.ended = false and p.end <= {currentTime} and p.next_period_id is not null);""".stripMargin
    val (leagueIds, periodIds) = SQL(q).on("currentTime" -> currentTime).as((long("league_id") ~ long("period_id")).*).map(x => (x._1, x._2)).toList.unzip
    postEndPeriodHook(leagueIds, periodIds, currentTime)
  }
  override def startPeriods(currentTime: LocalDateTime)(implicit c: Connection) = {
    val q =
      """select * from league l join period p using(league_id)
        |where (l.current_period_id.isNull or not(l.current_period_id === p.id)) and
        |p.ended = false and p.start <= {currentTime};""".stripMargin
    SQL(q).on("currentTime" -> currentTime).as((leagueParser ~ periodParser).*).
      map(x => postStartPeriodHook(x._1, x._2, currentTime))
  }

  override def getLimitTypes(leagueId: Long)(implicit c: Connection): Iterable[LimitType] = {
    from(limitTypeTable)(ft => where(ft.leagueId === leagueId)
      select ft
    )
  }
}

