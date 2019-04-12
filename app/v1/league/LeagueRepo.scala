package v1.league

import java.sql.Connection

import javax.inject.{Inject, Singleton}
import java.time.LocalDateTime

import scala.util.Try

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class LeagueFull(league: PublicLeagueRow, limits: Map[String, Iterable[LimitRow]], periods: Iterable[PeriodRow], currentPeriod: Option[PeriodRow], statFields: Iterable[String])

object LeagueFull{
  implicit val implicitWrites = new Writes[LeagueFull] {
    def writes(league: LeagueFull): JsValue = {
      Json.obj(
        "id" -> league.league.leagueId,
        "name" -> league.league.name,
        "gameId" -> league.league.gameId,
        "tournamentId" -> league.league.tournamentId,
        "isPrivate" -> league.league.isPrivate,
        "tournamentId" -> league.league.tournamentId,
        "teamSize" -> league.league.teamSize,
        "transferLimit" -> league.league.transferLimit, // use -1 for no transfer limit I think. only applies after period 1 start
        "transferWildcard" -> league.league.transferWildcard,
        "transferOpen" -> league.league.transferOpen,
        "transferDelayMinutes" -> league.league.transferDelayMinutes,
        "transferBlockedDuringPeriod" -> league.league.transferBlockedDuringPeriod,
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
  def get(id: Long)(implicit c: Connection): Option[LeagueRow]
  def getWithRelated(id: Long)(implicit c: Connection): LeagueFull
  def insert(formInput: LeagueFormInput)(implicit c: Connection): LeagueRow
  def update(league: LeagueRow, input: UpdateLeagueFormInput)(implicit c: Connection): LeagueRow
  def getStatFields(league: LeagueRow)(implicit c: Connection): Iterable[LeagueStatFieldRow]
  def getStatFieldNames(statFields: Iterable[LeagueStatField]): Array[String]
  def isStarted(league: LeagueRow): Boolean
  def insertLeagueStatField(leagueId: Long, name: String)(implicit c: Connection): Long
  def insertLeaguePrize(leagueId: Long, description: String, email: String)(implicit c: Connection): Long
  def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long])(implicit c: Connection): Long
  def getPeriod(periodId: Long)(implicit c: Connection): Option[PeriodRow]
  def getPeriods(league: LeagueRow)(implicit c: Connection): Iterable[PeriodRow]
  def getPeriodFromValue(leagueId: Long, value: Int)(implicit c: Connection): PeriodRow
  def getPeriodBetween(leagueId: Long, time: LocalDateTime)(implicit c: Connection): Option[PeriodRow]
  def getCurrentPeriod(league: LeagueRow)(implicit c: Connection): Option[PeriodRow]
  def getNextPeriod(league: LeagueRow)(implicit c: Connection): Either[Result, PeriodRow]
  def detailedLeagueQueryExtractor(rows: Iterable[PublicLeagueRow]): LeagueFull // TODO private
  def updatePeriod(
                    leagueId: Long, periodValue: Int, start: Option[LocalDateTime], end: Option[LocalDateTime],
                    multiplier: Option[Double])(implicit c: Connection): Int
  def postStartPeriodHook(league: LeagueRow, period: PeriodRow, timestamp: LocalDateTime)(implicit c: Connection)
  def postEndPeriodHook(periodIds: Iterable[Long], leagueIds: Iterable[Long], timestamp: LocalDateTime)(implicit c: Connection)
  def startPeriods(currentTime: LocalDateTime)(implicit c: Connection)
  def endPeriods(currentTime: LocalDateTime)(implicit c: Connection)
  def insertLimits(leagueId: Long, limits: Iterable[LimitTypeInput])(implicit c: Connection): Map[String, Long]
  def getStatField(leagueId: Long, statFieldName: String)(implicit c: Connection): Option[Long]
}

@Singleton
class LeagueRepoImpl @Inject()(implicit ec: LeagueExecutionContext) extends LeagueRepo{

  private val periodParser: RowParser[PeriodRow] = Macro.namedParser[PeriodRow](ColumnNaming.SnakeCase)
  private val leagueParser: RowParser[LeagueRow] = Macro.namedParser[LeagueRow](ColumnNaming.SnakeCase)
  private val detailedLeagueParser: RowParser[DetailedLeagueRow] = Macro.namedParser[DetailedLeagueRow](ColumnNaming.SnakeCase)

  override def get(id: Long)(implicit c: Connection): Option[LeagueRow] = {
    SQL("select * from league where league_id = {id};").on("id" -> id).as(leagueParser.singleOpt)
  }


  override def getWithRelated(id: Long)(implicit c: Connection): LeagueFull = {
    val queryResult = SQL("""select league_id, l.name as league_name, game_id, is_private , tournament_id, pickee_description, period_description,
          transfer_limit, transfer_wildcard, starting_money, team_size, transferDelayMinutes, transfer_open, transfer_blocked_during_period,
          url, url_verified, apply_points_at_start_time, no_wildcard_for_late_register,
           (cp is null) as started, (cp is not null and cp.end < now()) as ended,
           p.value as period_value, start, "end", multiplier, (p.id = l.current_period_id) as current, sf.name as stat_field_name,
           lt.name as limit_type_name, lt.description, l.name as limit_name, l."max" as limit_max
 | from league l join period p using(league_id) left join limit_type lt using(league_id)
 | left join current_period cp on (cp.league_id = l.league_id and cp.period_id = l.current_period_id)
           left join "limit" lim using(limit_type_id) join league_stat_field sf using(league_id)
        where league_id = {id} order by p.value, pck.external_id;""").on("id" -> id).as(detailedLeagueParser.*)
    detailedLeagueQueryExtractor(queryResult)
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

  override def insertLeaguePrize(leagueId: Long, description: String, email: String)(implicit c: Connection): Long = {
    val q = "insert into league_prize(league_id, description, email) values ({leagueId}, {description}, {email});"
    SQL(q).onParams(leagueId, description, email).executeInsert().get
  }

  override def insertLeagueStatField(leagueId: Long, name: String)(implicit c: Connection): Long = {
    val q = "insert into stat_field(league_id, name) values ({leagueId}, {name});"
    SQL(q).onParams(leagueId, name).executeInsert().get
  }

  override def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long])(implicit c: Connection): Long = {
    val q =
      """insert into period(league_id, value, start, "end", multiplier, next_period_id) values (
        |{leagueId}, {value}, {start}, {end}, {multiplier},{nextPeriodId}
        |);""".stripMargin
    SQL(q).onParams(leagueId, period, input.start, input.end, input.multiplier, nextPeriodId).executeInsert().get
  }

  override def getPeriod(periodId: Long)(implicit c: Connection): Option[PeriodRow] = {
    val q = "select * from period where period_id = {periodId};"
    SQL(q).on("periodId" -> periodId).as(periodParser.singleOpt)
  }

  override def getPeriods(league: LeagueRow)(implicit c: Connection): Iterable[PeriodRow] = {
    val q = "select * from period where league_id = {leagueId};"
    SQL(q).on("leagueId" -> league.id).as(periodParser.*)
  }

  override def getPeriodFromValue(leagueId: Long, value: Int)(implicit c: Connection): PeriodRow = {
    val q = "select * from period where league_id = {leagueId} and value = {value};"
    SQL(q).on("leagueId" -> league.id, "value" -> value).as(periodParser.single)
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

  override def detailedLeagueQueryExtractor(rows: Iterable[DetailedLeagueRow]): LeagueFull = {
    val head = rows.head
    val league = PublicLeagueRow(
      head.leagueId, head.leagueName, head.gameId, head.isPrivate, head.tournamentId, head.pickeeDescription, head.periodDescription,
      head.transferLimit, head.transferWildcard, head.startingMoney, head.teamSize, head.transferDelayMinutes,
      head.transferOpen, head.transferBlockedDuringPeriod, head.url, head.urlVerified, head.applyPointsAtStartTime,
      head.noWildcardForLateRegister, head.started, head.ended
    )
    val statFields = rows.flatMap(_.statFieldName)
    // TODO add current
    val periods = rows.map(
      r => PeriodRow(-1, -1, r.periodValue, r.start, r.end, r.multiplier)
    )
    val currentPeriod = rows.withFilter(_.current).map(r => PeriodRow(-1, -1, r.periodValue, r.start, r.end, r.multiplier)).headOption
    // TODO think this filter before group by inefficient
    val limits: Map[String, Iterable[LimitRow]] = rows.filter(_.limitTypeName.isDefined).groupBy(_.limitTypeName.get).mapValues(
      v => v.map(x => LimitRow(x.limitName.get, x.limitMax.get))
    )
    LeagueFull(league, limits, periods, currentPeriod, statFields)
  }

  override def updatePeriod(
                             leagueId: Long, periodValue: Int, start: Option[LocalDateTime], end: Option[LocalDateTime],
                             multiplier: Option[Double])(implicit c: Connection): Int = {
    var setString: String = ""
    var params: collection.mutable.Seq[NamedParameter] =
      collection.mutable.Seq(
        NamedParameter("value", toParameterValue(periodValue)),
        NamedParameter("league_id", toParameterValue(leagueId)))

    if (start.isDefined) {
      setString += ", [start] = {start}"
      params = params :+ NamedParameter("start", toParameterValue(start.get))
    }
    if (end.isDefined) {
      setString += ", [end] = {end}"
      params = params :+ NamedParameter("end", toParameterValue(end.get))
    }
    if (multiplier.isDefined) {
      setString += ", [multiplier] = {multiplier}"
      params = params :+ NamedParameter("multiplier", toParameterValue(multiplier.get))
    }
    SQL(
      "update period set " + setString + " WHERE [value] = {value} and [league_id] = {league_id}"
    ).on(params:_*).executeUpdate()
//    val period = from(periodTable)(p =>
//        where(p.leagueId === leagueId and p.value === periodValue)
//        select(p)
//      ).single
//    period.start = start.getOrElse(period.start)
//    period.end = end.getOrElse(period.end)
//    period.multiplier = multiplier.getOrElse(period.multiplier)
//    periodTable.update(period)
//    period
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
    }).reduce(_ ++ _)
  }

  override def getStatField(leagueId: Long, statFieldName: String)(implicit c: Connection): Option[Long] = {
    SQL(
      "select stat_field_id from stat_field where league_id = $leagueId and name = $statFieldName"
    ).as(long('stat_field_id').singleOpt)
  }
}

