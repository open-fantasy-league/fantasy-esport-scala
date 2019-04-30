package v1.leagueuser

import java.sql.Connection
import java.time.LocalDateTime

import play.api.Logger
//import java.math.BigDecimal

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import play.api.db._
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import anorm.SqlParser.long

import models._
import utils.GroupByOrderedImplicit._

import v1.team.TeamRepo
import v1.transfer.TransferRepo
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo

case class Ranking(userId: Long, username: String, value: Double, rank: Int, previousRank: Option[Int], team: Option[Iterable[PickeeRow]],
                   showTeam: Boolean = true)

case class LeagueRankings(leagueId: Long, leagueName: String, statField: String, rankings: Iterable[Ranking])

case class LeagueWithLeagueUser(league: LeagueRow, info: LeagueUserRow)

case class RankingRow(
                       externalUserId: Long, username: String, leagueUserId: Long, value: Double, previousRank: Option[Int],
                       internalPickeeId: Option[Long], externalPickeeId: Option[Long],
                       pickeeName: Option[String], price: Option[BigDecimal]
                     )

object LeagueWithLeagueUser {
  implicit val implicitWrites = new Writes[LeagueWithLeagueUser] {
    def writes(x: LeagueWithLeagueUser): JsValue = {
      Json.obj(
        "league" -> x.league,
        "userInfo" -> x.info
      )
    }
  }
}

object Ranking{
  implicit val implicitWrites = new Writes[Ranking] {
    def writes(ranking: Ranking): JsValue = {
      Json.obj(
        "userId" -> ranking.userId,
        "username" -> ranking.username,
        "value" -> ranking.value,
        "rank" -> ranking.rank,
        "previousRank" -> ranking.previousRank,
        "team" ->  ranking.team
      )
    }
  }
}

object LeagueRankings{
  implicit val implicitWrites = new Writes[LeagueRankings] {
    def writes(leagueRank: LeagueRankings): JsValue = {
      Json.obj(
        "leagueId" -> leagueRank.leagueId,
        "leagueName" -> leagueRank.leagueName,
        "rankings" -> leagueRank.rankings,
        "statField" -> leagueRank.statField,
      )
    }
  }
}

case class DetailedLeagueUser(leagueUser: LeagueUserRow, team: Option[Iterable[PickeeRow]], scheduledTransfers: Option[Iterable[TransferRow]], stats: Option[Map[String, Double]])

object DetailedLeagueUser{
  implicit val implicitWrites = new Writes[DetailedLeagueUser] {
    def writes(x: DetailedLeagueUser): JsValue = {
      Json.obj(
        "leagueUser" -> x.leagueUser,
        "team" -> x.team,
        "scheduledTransfers" -> x.scheduledTransfers,
        "stats" -> x.stats
      )
    }
  }
}

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait LeagueUserRepo{
  def getWithUser(leagueId: Long, externalUserId: Long)(implicit c: Connection): Option[LeagueUserRow]
  def detailedLeagueUser(
                          leagueUser: LeagueUserRow, showTeam: Boolean, showScheduledTransfers: Boolean,
                          stats: Boolean)(implicit c: Connection): DetailedLeagueUser
  def getAllLeaguesForUser(userId: Long)(implicit c: Connection): Iterable[LeagueWithLeagueUser]
  def getAllUsersForLeague(leagueId: Long)(implicit c: Connection): Iterable[LeagueUserRow]
  def insertLeagueUser(league: LeagueRow, userId: Long)(implicit c: Connection): LeagueUserRow
  def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long)(implicit c: Connection): Long
  def insertLeagueUserStatDaily(leagueUserStatId: Long, period: Option[Int])(implicit c: Connection): Long
  def update(
              leagueUserId: Long, money: BigDecimal, remainingTransfers: Option[Int], changeTstamp: Option[LocalDateTime],
              appliedWildcard: Boolean
            )(implicit c: Connection): Unit
  def getRankings(
                   league: LeagueRow, statFieldId: Long, period: Option[Int], userIds: Option[Array[Long]],
                   secondaryOrdering: Option[List[Long]], showTeam: Boolean
                 )(implicit c: Connection): LeagueRankings
  def leagueUserStatsAndTeamQuery(leagueId: Long, statFieldId: Long, period: Option[Int],
                                   timestamp: Option[LocalDateTime], secondaryOrdering: Option[List[Long]]
                                 )(implicit c: Connection): Iterable[RankingRow]
  def getLeagueUserStats(
                          leagueId: Option[Long], leagueUserId: Option[Long], statFieldId: Option[Long], period: Option[Int],
                          orderByValue: Boolean
                        )(implicit c: Connection): Iterable[LeagueUserStatDailyRow]
  def getLeagueUserStatsAndTeam(
                                 league: LeagueRow, statFieldId: Long, period: Option[Int], timestamp: Option[LocalDateTime],
                                 secondaryOrdering: Option[List[Long]])(implicit c: Connection): Iterable[RankingRow]
  def updatePreviousRank(leagueUserId: Long, statFieldId: Long, previousRank: Int)(implicit c: Connection): Unit
  def joinUsers(userIds: Iterable[Long], league: LeagueRow): Iterable[LeagueUserRow]
  def userInLeague(userId: Long, leagueId: Long)(implicit c: Connection): Boolean
  def getShouldProcessTransfer(leagueId: Long)(implicit c: Connection): Iterable[Long]
  def updateHistoricRanks(leagueId: Long)(implicit c: Connection)
}

@Singleton
class LeagueUserRepoImpl @Inject()(db: Database, transferRepo: TransferRepo, teamRepo: TeamRepo, leagueRepo: LeagueRepo, pickeeRepo: PickeeRepo)(implicit ec: LeagueExecutionContext) extends LeagueUserRepo{
  private val logger = Logger(getClass)
  override def getWithUser(leagueId: Long, externalUserId: Long)
                          (implicit c: Connection): Option[LeagueUserRow] = {
    SQL(s"""select user_id, username, external_user_id, league_user_id, money, entered, remaining_transfers, used_wildcard, change_tstamp
      from league_user join useru using(user_id) where league_id = $leagueId and external_user_id = $externalUserId;""").as(LeagueUserRow.parser.singleOpt)
  }

  override def detailedLeagueUser(
                                   leagueUser: LeagueUserRow, showTeam: Boolean, showScheduledTransfers: Boolean,
                                   showStats: Boolean)(implicit c: Connection): DetailedLeagueUser = {
    val team = showTeam match {
      case false => None
      case true => {
        Some(teamRepo.getLeagueUserTeam(leagueUser.leagueUserId))
      }
    }
    val scheduledTransfers = if (showScheduledTransfers) Some(transferRepo.getLeagueUserTransfer(leagueUser.leagueUserId, Some(false))) else None
    val stats = if (showStats) {
      Some(getLeagueUserStats(
        Option.empty[Long], Some(leagueUser.leagueUserId), None, None, false
      ).map(x => x.statFieldName -> x.value).toMap)
    }
    else None
    DetailedLeagueUser(leagueUser, team, scheduledTransfers, stats)
  }

  override def getAllLeaguesForUser(userId: Long)(implicit c: Connection): Iterable[LeagueWithLeagueUser] = {
    val leagueIds = SQL(
      s"select league_id from leagues join league_user using(league_id) join useru using(user_id) where external_user_id = $userId"
    ).as(SqlParser.scalar[Long].*)
    leagueIds.map(lid => LeagueWithLeagueUser(leagueRepo.get(lid).get, getWithUser(lid, userId).get))
  }

  override def getAllUsersForLeague(leagueId: Long)(implicit c: Connection): Iterable[LeagueUserRow] = {
    SQL("select user_id, username, external_user_id, league_user_id, money, entered, remaining_transfers, used_wildcard, change_tstamp" +
      "from league_user join user where league_id = $leagueId").as(LeagueUserRow.parser.*)
  }

  override def insertLeagueUser(league: LeagueRow, userId: Long)(implicit c: Connection): LeagueUserRow = {
    println("inserting league user")
    SQL(
      """
        |insert into league_user(league_id, user_id, money, entered, remaining_transfers, used_wildcard) values
        |({leagueId}, (select user_id from useru where external_user_id = {userId} limit 1), {startingMoney}, {entered},
        | {remainingTransfers}, {usedWildcard}) returning league_user_id;
      """.stripMargin).on(
      "leagueId" -> league.leagueId, "userId" -> userId, "startingMoney" -> league.startingMoney,
      "entered" -> LocalDateTime.now(), "remainingTransfers" -> league.transferLimit,
      // dont give wildcard to people who join league late
      "usedWildcard" -> (!league.transferWildcard || (leagueRepo.isStarted(league) && league.noWildcardForLateRegister))
    ).executeInsert().get
    println("executed insert league user")
      getWithUser(league.leagueId, userId).get
  }

  override def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long)(implicit c: Connection): Long = {
    SQL(
      s"insert into league_user_stat(stat_field_id, league_user_id, previous_rank) VALUES ($statFieldId, $leagueUserId, 1) returning league_user_stat_id;"
    ).executeInsert().get
  }

  override def insertLeagueUserStatDaily(leagueUserStatId: Long, period: Option[Int])(implicit c: Connection): Long = {
    SQL(
      """insert into league_user_stat_period(league_user_stat_id, period, value) VALUES
        |({leagueUserStatId}, {period}, 0) returning league_user_stat_period_id;""".stripMargin
    ).on("leagueUserStatId" -> leagueUserStatId, "period" -> period).executeInsert().get
  }

  override def update(
                       leagueUserId: Long, money: BigDecimal, remainingTransfers: Option[Int], changeTstamp: Option[LocalDateTime],
                       appliedWildcard: Boolean
                     )(implicit c: Connection): Unit = {
    val usedWildcardSet = if (appliedWildcard) ", used_wildcard = true" else ""
    SQL(s"""update league_user set money = {money}, remaining_transfers = {remainingTransfers}, change_tstamp = {changeTstamp} $usedWildcardSet where league_user_id = {leagueUserId}""")
      .on("money" -> money, "remainingTransfers" -> remainingTransfers, "changeTstamp" -> changeTstamp, "leagueUserId" -> leagueUserId).executeUpdate()
  }

  override def getRankings(
                            league: LeagueRow, statFieldId: Long, period: Option[Int],
                            userIds: Option[Array[Long]], secondaryOrdering: Option[List[Long]], showTeam: Boolean
                          )(implicit c: Connection): LeagueRankings = {
      println(s"getrankings: userIds: ${userIds.map(_.toList.mkString(",")).getOrElse("None")}")
      val qResult = getLeagueUserStatsAndTeam(league, statFieldId, period, None, secondaryOrdering).toList
      val filteredByUsers = if (userIds.isDefined) qResult.filter(q => userIds.get.toList.contains(q.externalUserId)) else qResult
      val stats = filteredByUsers.groupByOrdered(_.externalUserId).toList
      var lastScore = Double.MaxValue
      var lastScoreRank = 0
      val tmp = stats.map({case (u, v) => {
        val team = v.withFilter(_.internalPickeeId.isDefined).map(v2 => PickeeRow(
          v2.internalPickeeId.get, v2.externalPickeeId.get, v2.pickeeName.get, v2.price.get)
        )
        (v.head, team)}
      })
      val rankings = tmp.zipWithIndex.map({case ((q, team), i) => {
        println(f"i: $i")
        val value = q.value
        println(f"value: $value")
        val rank = if (value == lastScore) lastScoreRank else i + 1
        println(f"rank: $rank")
        lastScore = value
        lastScoreRank = rank
        Ranking(q.externalUserId, q.username, value, rank, q.previousRank, if (showTeam) Some(team) else None)
      }})

    LeagueRankings(
      league.leagueId, league.leagueName, leagueRepo.getStatFieldName(statFieldId).get, rankings
    )
  }

  override def getLeagueUserStats(
                                   leagueId: Option[Long], leagueUserId: Option[Long], statFieldId: Option[Long],
                                   period: Option[Int], orderByValue: Boolean
                                 )(implicit c: Connection): Iterable[LeagueUserStatDailyRow] = {
    // todo assert either league or league user id non empty XOR
    // they are both nullable so that this func can work for either getting all league-users, or just one
    logger.debug("getLeagueUserStats")
    val periodFilter = if (period.isEmpty) "is null" else s"= ${period.get}"
    val statFieldFilter = if (statFieldId.isEmpty) "" else s"sf.stat_field_id = ${statFieldId.get} and"
    val leagueUserFilter = if (leagueUserId.isEmpty) "" else s"lu.league_user_id = ${leagueUserId.get} and"
    val leagueFilter = if (leagueId.isEmpty) "" else s"league_id = ${leagueId.get} and"
    val orderByValueStr = if (orderByValue) "" else "order by value desc"
    val sql = s"""
                 |select lu.league_user_id, sf.name as stat_field_name, previous_rank, value, period from league_user lu join stat_field sf using(league_id)
                 |join league_user_stat lus on(sf.stat_field_id = lus.stat_field_id and lu.league_user_id = lus.league_user_id)
                 |join league_user_stat_period lusd using(league_user_stat_id)
                 |where $leagueFilter $leagueUserFilter $statFieldFilter period $periodFilter $orderByValueStr;
                 |
      """.stripMargin
    logger.debug(s"sql: $sql")
    SQL(sql).as(LeagueUserStatDailyRow.parser.*)
  }

  override def leagueUserStatsAndTeamQuery(
    leagueId: Long, statFieldId: Long, period: Option[Int],
    timestamp: Option[LocalDateTime], secondaryOrdering: Option[List[Long]]
  )(implicit c: Connection): Iterable[RankingRow] = {
    val rankingParser: RowParser[RankingRow] = Macro.namedParser[RankingRow](ColumnNaming.SnakeCase)
    println(timestamp)
    println(period)
    val timestampFilter = if (timestamp.isDefined) "t.timespan @> {timestamp}::timestamptz" else "upper(t.timespan) is NULL"
    val periodFilter = if (period.isDefined) "lusd.period = {period}" else "lusd.period is NULL"
    val q = secondaryOrdering match {
      case None => s"""select u.external_user_id, u.username, lu.league_user_id, lusd.value, lus.previous_rank,
                  pickee_id as internal_pickee_id, external_pickee_id, p.pickee_name, p.price from useru u
           join league_user lu using(user_id)
           left join team t on (t.league_user_id = lu.league_user_id and $timestampFilter)
           left join pickee p using(pickee_id)
           join league_user_stat lus on (lus.league_user_id = lu.league_user_id and lus.stat_field_id = {statFieldId})
           join league_user_stat_period lusd on (lusd.league_user_stat_id = lus.league_user_stat_id and $periodFilter)
           order by lusd.value desc;
           """
      case Some(secondary) => {
        val extraJoins = secondary.map(s =>
        s"""join league_user_stat lus$s on (lus$s.league_user_id = lu.league_user_id and lus$s.stat_field_id = $s)
            join league_user_stat_period lusd$s on (lusd$s.league_user_stat_id = lus$s.league_user_stat_id and $periodFilter)
            """).mkString(" ")
        val extraOrder = secondary.map(s => s"lusd$s.value desc").mkString(" ")
        s"""select u.external_user_id, u.username, lu.league_user_id, lusd.value, lus.previous_rank, pickee_id as internal_pickee_id, external_pickee_id
            p.pickee_name, p.price from useru u
             join league_user lu using(user_id)
             left join team t on (t.league_user_id = lu.league_user_id and $timestampFilter)
             left join pickee p using(pickee_id)
             join league_user_stat lus on (lus.league_user_id = lu.league_user_id and lus.stat_field_id = {statFieldId})
             join league_user_stat_period lusd on (lusd.league_user_stat_id = lus.league_user_stat_id and $periodFilter)
             $extraJoins
             order by lusd.value desc $extraOrder;
             """
      }
    }
    println(q)
    SQL(q).on("timestamp" -> timestamp, "period" -> period, "statFieldId" -> statFieldId).as(rankingParser.*)
  }

  override def getLeagueUserStatsAndTeam(
                                          league: LeagueRow, statFieldId: Long, period: Option[Int],
                                          timestamp: Option[LocalDateTime], secondaryOrdering: Option[List[Long]]
                                        )(implicit c: Connection):
    Iterable[RankingRow] = {
    // hahaha. rofllwefikl!s
    (period, leagueRepo.getCurrentPeriod(league), timestamp) match {
      case (None, _, None) => this.leagueUserStatsAndTeamQuery(league.leagueId, statFieldId, None, None, secondaryOrdering)
      case (_, None, _) => this.leagueUserStatsAndTeamQuery(league.leagueId, statFieldId, None, None, secondaryOrdering)
      case (Some(periodVal), Some(currentPeriod), None) if periodVal == currentPeriod.value =>
        this.leagueUserStatsAndTeamQuery(league.leagueId, statFieldId, Some(periodVal), None,secondaryOrdering)
      case (Some(_), _, Some(_)) => throw new Exception("Specify period, or timestamp. not both")
      case (None, _, Some(t)) => {
        val period = leagueRepo.getPeriodFromTimestamp(league.leagueId, t).get
        leagueUserStatsAndTeamQuery(league.leagueId, statFieldId, Some(period.value), Some(t), secondaryOrdering)
      }
      case (Some(pVal), _, None) => {
        println("cat")
        val endPeriodTstamp = leagueRepo.getPeriodFromValue(league.leagueId, pVal).end
        leagueUserStatsAndTeamQuery(league.leagueId, statFieldId, Some(pVal), Some(endPeriodTstamp), secondaryOrdering)
      }
    }
  }

  override def updatePreviousRank(leagueUserId: Long, statFieldId: Long, previousRank: Int)(implicit c: Connection): Unit = {
    SQL(
      "update league_user_stat set previous_rank = {previousRank} where league_user_id = {leagueUserId} and stat_field_id = {statFieldId};"
    ).on("previousRank" -> previousRank, "leagueUserId" -> leagueUserId, "statFieldId" -> statFieldId).executeUpdate()
  }

  override def joinUsers(userIds: Iterable[Long], league: LeagueRow): Iterable[LeagueUserRow] = {
    db.withConnection { implicit c: Connection =>
      println("in joinusers")
      val newLeagueUsers = userIds.map(uid => insertLeagueUser(league, uid))
      val newLeagueUserStatIds = leagueRepo.getStatFields(league.leagueId).flatMap(sf => newLeagueUsers.map({
        nlu => insertLeagueUserStat(sf.statFieldId, nlu.leagueUserId)
      }))
      newLeagueUserStatIds.foreach(sid => insertLeagueUserStatDaily(sid, None))

      leagueRepo.getPeriods(league.leagueId).foreach(p =>
        newLeagueUserStatIds.foreach(sid => insertLeagueUserStatDaily(sid, Some(p.value)))
      )

      newLeagueUsers
    }
  }

  override def userInLeague(userId: Long, leagueId: Long)(implicit c: Connection): Boolean = {
    SQL(s"select 1 from league_user where league_id = $leagueId and user_id = $userId").
      as(SqlParser.scalar[Int].singleOpt).isDefined
  }

  override def getShouldProcessTransfer(leagueId: Long)(implicit c: Connection): Iterable[Long] = {
    val q = "select league_user_id from league_user where league_id = {leagueId} and change_tstamp <= now();"
    SQL(q).on("leagueId" -> leagueId).as(long("league_user_id").*)
  }

  override def updateHistoricRanks(leagueId: Long)(implicit c: Connection): Unit = {
    leagueRepo.getStatFields(leagueId).foreach(sf => {
      val leagueUserStatsOverall = getLeagueUserStats(Some(leagueId), None, Some(sf.statFieldId), None, true)
      var lastScore = Double.MaxValue
      var lastScoreRank = 0
      leagueUserStatsOverall.zipWithIndex.map({
        case (row, i) => {
          val value = row.value
          val rank = if (value == lastScore) lastScoreRank else i + 1
          lastScore = value
          lastScoreRank = rank
          val previousRank = rank
          (row.leagueUserId, sf.statFieldId, previousRank)
        }
      }).foreach(x => (updatePreviousRank _).tupled(x))
      // can do all update in one call if append then update outside loop
  // TODO reimplement this
//      val pickeeStatsOverall = pickeeRepo.getPickeeStat(league.leagueId, sf.statFieldId, None).map(_._1)
//      val newPickeeStat = pickeeStatsOverall.zipWithIndex.map(
//        { case (p, i) => p.previousRank = i + 1; p }
//      )
//      // can do all update in one call if append then update outside loop
//      pickeeStatTable.update(newPickeeStat)
    })
  }
}

