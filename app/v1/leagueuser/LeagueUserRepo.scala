package v1.leagueuser

import java.sql.Connection
import java.time.LocalDateTime
//import java.math.BigDecimal

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import play.api.db._
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import anorm.SqlParser.long

import scala.util.Try
import models._
import utils.GroupByOrderedImplicit._

import v1.team.TeamRepo
import v1.transfer.TransferRepo
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo

case class Ranking(userId: Long, username: String, value: Double, rank: Int, previousRank: Option[Int], team: Option[Iterable[PickeeRow]])

case class LeagueRankings(leagueId: Long, leagueName: String, statField: String, rankings: Iterable[Ranking])

case class LeagueWithLeagueUser(league: LeagueRow, info: LeagueUserRow)

case class RankingRow(
                       externalUserId: Long, username: String, leagueUserId: Long, value: Double, previousRank: Option[Int], pickeeId: Option[Long],
                       pickeeName: Option[String], cost: Option[BigDecimal]
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

// TODO conditional fields
object Ranking{
  implicit val implicitWrites = new Writes[Ranking] {
    def writes(ranking: Ranking): JsValue = {
      Json.obj(
        "userId" -> ranking.userId,
        "username" -> ranking.username,
        "value" -> ranking.value,
        "rank" -> ranking.rank,
        "previousRank" -> ranking.previousRank,
        "team" -> ranking.team
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

case class DetailedLeagueUser(leagueUser: LeagueUserRow, team: Option[List[PickeeRow]], scheduledTransfers: Option[Iterable[TransferRow]], stats: Option[Map[String, Double]])

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
              leagueUserId: Long, money: BigDecimal, remainingTransfers: Option[Int], changeTstamp: LocalDateTime,
              appliedWildcard: Boolean
            )(implicit c: Connection): Unit
  def getRankings(
                   league: LeagueRow, statFieldId: Long, period: Option[Int], userIds: Option[Array[Long]],
                   secondaryOrdering: Option[List[Long]]
                 )(implicit c: Connection): LeagueRankings
  def leagueUserStatsAndTeamQuery(leagueId: Long, statFieldId: Long, period: Option[Int],
                                   timestamp: Option[LocalDateTime], secondaryOrdering: Option[List[Long]]
                                 )(implicit c: Connection): Iterable[RankingRow]
  def getLeagueUserStats(
                          leagueId: Long, leagueUserId: Option[Long], statFieldId: Option[Long], period: Option[Int],
                          orderByValue: Boolean
                        ): Iterable[LeagueUserStatDailyRow]
  def getLeagueUserStatsAndTeam(
                                 league: LeagueRow, statFieldId: Long, period: Option[Int], timestamp: Option[LocalDateTime],
                                 secondaryOrdering: Option[List[Long]])(implicit c: Connection): Iterable[RankingRow]
  def updatePreviousRank(leagueUserId: Long, statFieldId: Long, previousRank: Int)(implicit c: Connection): Unit
  def joinUsers(userIds: Iterable[Long], league: LeagueRow)(implicit c: Connection): Iterable[LeagueUserRow]
  def userInLeague(userId: Long, leagueId: Long)(implicit c: Connection): Boolean
  def getShouldProcessTransfer(leagueId: Long)(implicit c: Connection): Iterable[Long]
  def updateHistoricRanks(league: League)(implicit c: Connection)

  //private def statFieldIdFromName(statFieldName: String, leagueId: Long)
}

@Singleton
class LeagueUserRepoImpl @Inject()(db: Database, transferRepo: TransferRepo, teamRepo: TeamRepo, leagueRepo: LeagueRepo, pickeeRepo: PickeeRepo)(implicit ec: LeagueExecutionContext) extends LeagueUserRepo{

  override def getWithUser(leagueId: Long, externalUserId: Long)
                          (implicit c: Connection): Option[LeagueUserRow] = {
    SQL("select user_id, username, external_user_id, league_user_id, money, entered, remianing_transfers, used_wildcard, change_tstamp" +
      "from league_user join user where league_id = {} and external_user_id = {};").
      onParams(leagueId, externalUserId).as(LeagueUserRow.parser.singleOpt)
  }

  override def detailedLeagueUser(
                                   leagueUser: LeagueUserRow, showTeam: Boolean, showScheduledTransfers: Boolean,
                                   showStats: Boolean): DetailedLeagueUser = {
    db.withConnection { implicit c =>
      val team = showTeam match {
        case false => None
        case true => {
          Some(teamRepo.getLeagueUserTeam(leagueUser.leagueUserId))
        }
      }
      // todo boolean to option?
      val scheduledTransfers = showScheduledTransfers match {
        case false => None
        case true => {
          Some(transferRepo.getLeagueUserTransfer(leagueUser, Some(false)))
        }
      }
      val stats = showStats match {
        case false => None
        case true => {
          Some(getLeagueUserStats(
            leagueUser.leagueId, Some(leagueUser.leagueUserId), None, None, false
          )).map(x => x.statFieldName -> x.value).toMap
        }
      }
    }
    DetailedLeagueUser(leagueUser, team, scheduledTransfers, stats)
  }

  override def getAllLeaguesForUser(userId: Long)(implicit c: Connection): Iterable[LeagueWithLeagueUser] = {
    val leagueIds = SQL(
      "select league_id from leagues join league_user using(league_id) join useru using(user_id) where external_user_id = {}"
    ).onParams(userId).as(SqlParser.scalar[Long].*)
    leagueIds.map(lid => LeagueWithLeagueUser(leagueRepo.get(lid), getWithUser(lid, userId)))
  }

  override def getAllUsersForLeague(leagueId: Long)(implicit c: Connection): Iterable[LeagueUserRow] = {
    SQL("select user_id, username, external_user_id, league_user_id, money, entered, remianing_transfers, used_wildcard, change_tstamp" +
      "from league_user join user where league_id = {}").
      onParams(leagueId).as(LeagueUserRow.parser.*)
  }

  override def insertLeagueUser(league: LeagueRow, userId: Long)(implicit c: Connection): LeagueUserRow = {
    val leagueUserId = SQL(
      """
        |insert into league_user(league_id, user_id, money, entered, remaining_transfers, used_wildcard) values
        |({}, (select user_id from useru where external_user_id = {}), {}, {}, {}, {});
      """.stripMargin).onParams(league.leagueId, userId, league.startingMoney, LocalDateTime.now(), league.transferLimit,
      !league.transferWildcard || (leagueRepo.isStarted(league) && league.noWildcardForLateRegister)).executeInsert.get
    getWithUser(league.leagueId, userId)
    // dont give wildcard to people who join league late
  }

  override def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long)(implicit c: Connection): Long = {
    SQL(
      "insert into league_user_stat(stat_field_id, league_user_id) VALUES ({}, {});"
    ).onParams(statFieldId, leagueUserId).executeInsert().get
  }

  override def insertLeagueUserStatDaily(leagueUserStatId: Long, period: Option[Int]): Long = {
    SQL(
      "insert into league_user_stat_daily(league_user_stat_id, period) VALUES ({}, {});"
    ).onParams(leagueUserStatId, period).executeInsert().get
  }

  override def update(
                       leagueUserId: Long, money: BigDecimal, remainingTransfers: Option[Int], changeTstamp: LocalDateTime,
                       appliedWildcard: Boolean
                     )(implicit c: Connection): Unit = {
    val usedWildcardSet = if (appliedWildcard) ", used_wildcard = true" else ""
    val _ = SQL("update league_user set money = {}, remaining_transfers = {}, change_tstamp = {}{} where league_user_id = {}")
      .onParams(money, remainingTransfers, changeTstamp, usedWildcardSet, leagueUserId).executeUpdate()
  }

  override def getRankings(
                            league: LeagueRow, statFieldId: Long, period: Option[Int],
                            userIds: Option[Array[Long]], secondaryOrdering: Option[List[Long]]
                          )(implicit c: Connection): LeagueRankings = {
      println(s"getrankings: userIds: ${userIds.map(_.toList.mkString(",")).getOrElse("None")}")
      val qResult = getLeagueUserStatsAndTeam(league, statFieldId, period, None, secondaryOrdering).toList
      val filteredByUsers = if (userIds.isDefined) qResult.filter(q => userIds.get.toList.contains(q.userId)) else qResult
      val stats = filteredByUsers.groupByOrdered(_.userId).toList
      var lastScore = Double.MaxValue
      var lastScoreRank = 0
      val tmp = stats.map({case (u, v) => {
        val team = v.withFilter(_.pickeeId.isDefined).map(v2 => PickeeRow(v2.pickeeId.get, v2.pickeeName.get, v2.cost.get))
        (v.head, team)}
      })
      tmp.zipWithIndex.map({case ((q, team), i) => {
        println(f"i: $i")
        val value = q.value
        println(f"value: $value")
        val rank = if (value == lastScore) lastScoreRank else i + 1
        println(f"rank: $rank")
        lastScore = value
        lastScoreRank = rank
        Ranking(q.userId, q.username, value, rank, q.previousRank, Some(team))
      }})

    LeagueRankings(
      league.leagueId, league.name, statField.name, rankings
    )
  }

  override def getLeagueUserStats(
                                   leagueId: Long, leagueUserId: Option[Long], statFieldId: Option[Long],
                                   period: Option[Int], orderByValue: Boolean
                                 ): Iterable[LeagueUserStatDailyRow] = {
    val periodFilter = if (period.isEmpty) "is null" else s"= ${period.get}"
    val statFieldFilter = if (statFieldId.isEmpty) "" else s"sf.stat_field_id = ${statFieldId.get} and"
    val leagueUserFilter = if (leagueUserId.isEmpty) "" else s"lu.league_user_id = ${leagueUserId.get} and"
    val orderByValueStr = if (orderByValue.isEmpty) "" else "order by value desc"
    SQL(
      """
        |select league_user_id, stat_field_name, previous_rank, value, period from league_user lu join stat_field sf using(league_id)
        |join league_user_stat lus on(lus.league_id = lu.league_id and lus.stat_field_id = sf.stat_field_id)
        |join league_user_stat_daily lusd using(league_user_stat_id)
        |where lu.league_id = {} and {} {} period {} {};
        |
      """.stripMargin).onParams(
      leagueId, leagueUserFilter, statFieldFilter, periodFilter, orderByValueStr
    ).as(LeagueUserStatDailyRow.parser.*)
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
    // TODO nice injection
    val q = secondaryOrdering match {
      case None => s"""select u.external_user_id, u.username, lu.league_user_id, lusd.value, lus.previous_rank,
                  pickee_id, p.pickee_name, p.cost from useru u
           join league_user lu using(user_id)
           left join team t on (t.league_user_id = lu.league_user_id and #$timestampFilter)
           left join pickee p using(pickee_id)
           join league_user_stat lus on (lus.league_user_id = lu.league_user_id and lus.stat_field_id = {statFieldId})
           join league_user_stat_daily lusd on (lusd.league_user_stat_id = lus.league_user_stat_id and #$periodFilter)
           order by lusd.value desc;
           """
      case Some(secondary) => {
        val extraJoins = secondary.map(s =>
        s"""join league_user_stat lus$s on (lus$s.league_user_id = lu.league_user_id and lus$s.stat_field_id = $s)
            join league_user_stat_daily lusd$s on (lusd$s.league_user_stat_id = lus$s.league_user_stat_id and $periodFilter)
            """).mkString(" ")
        val extraOrder = secondary.map(s => s"lusd$s.value desc").mkString(" ")
        s"""select u.external_user_id, u.username, lu.league_user_id, lusd.value, lus.previous_rank, pickee_id,
            p.pickee_name, p.cost from useru u
             join league_user lu using(user_id)
             join team t on (t.league_user_id = lu.league_user_id and #$timestampFilter)
             left join pickee p using(pickee_id)
             join league_user_stat lus on (lus.league_user_id = lu.league_user_id and lus.stat_field_id = {statFieldId})
             join league_user_stat_daily lusd on (lusd.league_user_stat_id = lus.league_user_stat_id and #$periodFilter)
             #$extraJoins
             order by lusd.value desc #$extraOrder;
             """
      }
    }
    println(q)
    SQL(q).on ("timestamp" -> timestamp, "period" -> period, "statFieldId" -> statFieldId).as(rankingParser.*)
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
        val period = leagueRepo.getPeriodBetween(league.leagueId, t).get
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
      "update league_user_stat set previous_rank = {} where league_user_id = {} and stat_field_id = {};"
    ).onParams(previousRank, leagueUserId, statFieldId).executeUpdate()
  }

  override def joinUsers(userIds: Iterable[Long], league: LeagueRow)(implicit c: Connection): Iterable[LeagueUserRow] = {
    // TODO move to league user repo
    // // can ust pass stat field ids?
    val newLeagueUsers = userIds.map(uid => insertLeagueUser(league, uid))
    val newLeagueUserStats = leagueRepo.getStatFields(league).flatMap(sf => newLeagueUsers.map(nlu => insertLeagueUserStat(sf.statFieldId, nlu.leagueUserId)))

    newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.leagueUserId, None))

    leagueRepo.getPeriods(league).foreach(p =>
      newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.leagueUserId, Some(p.value)))
    )

    newLeagueUsers
  }

  override def userInLeague(userId: Long, leagueId: Long)(implicit c: Connection): Boolean = {
    SQL("select 1 from league_user where league_id = {} and user_id ={};").onParams(leagueId, userId).
      as(SqlParser.scalar[Int].singleOpt).isDefined
  }

  override def getShouldProcessTransfer(leagueId: Long)(implicit c: Connection): Iterable[Long] = {
    val q = "select league_user_id from league_user where league_id = {leagueId} and change_tstamp <= now();"
    SQL(q).on("leagueId" -> leagueId).as(long("league_user_id").*)
  }

  override def updateHistoricRanks(league: League)(implicit c: Connection) = {
    // TODO this needs to group by the stat field.
    // currently will do weird ranks
    league.statFields.foreach(sf => {
      val leagueUserStatsOverall = getLeagueUserStats(league.leagueId, None, Some(sf.statFieldId), None, true)
      var lastScore = Double.MaxValue // TODO java max num
      var lastScoreRank = 0
      val newLeagueUserStat = leagueUserStatsOverall.zipWithIndex.map({
        case (row, i) => {
          val value = row.value
          val rank = if (value == lastScore) lastScoreRank else i + 1
          lastScore = value
          lastScoreRank = rank
          val previousRank = rank
          (leagueUserId, sf.statFieldId, previousRank)
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

