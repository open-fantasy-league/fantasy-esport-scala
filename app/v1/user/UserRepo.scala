package v1.user

import play.api.libs.json.{JsValue, Json, Writes}
import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import anorm._
import anorm.{Macro, RowParser}
import Macro.ColumnNaming
import models._
import play.api.Logger
import play.api.db.Database
import utils.Utils
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo
import v1.team.TeamRepo

//case class Ranking(userId: Long, username: String, value: Double, ranking: Int, previousRank: Option[Int])

case class LeagueRankings(leagueId: Long, leagueName: String, statField: String, rankings: Iterable[RankingRow])

case class LeagueWithUser(league: LeagueRow, info: UserRow)

case class RankingRow(
                       externalUserId: Long, username: String, userId: Long, value: Double, previousRank: Option[Int], ranking: Int = 0
                     )

case class TeamWithPeriod(team: Iterable[CardOut], period: Int)
object TeamWithPeriod {
  implicit val implicitWrites = new Writes[TeamWithPeriod] {
    def writes(x: TeamWithPeriod): JsValue = {
      Json.obj(
        "team" -> x.team,
        "period" -> x.period
      )
    }
  }
}

object LeagueWithUser {
  implicit val implicitWrites = new Writes[LeagueWithUser] {
    def writes(x: LeagueWithUser): JsValue = {
      Json.obj(
        "league" -> x.league,
        "userInfo" -> x.info
      )
    }
  }
}

object RankingRow{
  implicit val implicitWrites = new Writes[RankingRow] {
    def writes(ranking: RankingRow): JsValue = {
      if (ranking.ranking == 0) {
        Json.obj(
          "userId" -> ranking.externalUserId,
          "username" -> ranking.username,
          "value" -> Utils.trunc(ranking.value, 1),
        )
      }
      else {
        Json.obj(
          "userId" -> ranking.externalUserId,
          "username" -> ranking.username,
          "value" -> Utils.trunc(ranking.value, 1),
          "rank" -> ranking.ranking,
          "previousRank" -> ranking.previousRank,
        )
      }
    }
  }

  val parser: RowParser[RankingRow] = Macro.namedParser[RankingRow](ColumnNaming.SnakeCase)
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


class UserExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait UserRepo{
  def update(userId: Long, leagueId: Long, input: UpdateUserFormInput)(implicit c: Connection): Unit
  def get(leagueId: Long, externalUserId: Long)(implicit c: Connection): Option[UserRow]
  def getUsers(userIds: List[Long])(implicit c: Connection): Iterable[UserRow]
  def getAllUsersForLeague(leagueId: Long)(implicit c: Connection): Iterable[UserRow]
  def insertUser(league: LeagueRow, userId: Long, username: String)(implicit c: Connection): UserRow
  def insertUserStat(statFieldId: Long, userId: Long)(implicit c: Connection): Long
  def insertUserStatDaily(userStatId: Long, period: Option[Int])(implicit c: Connection): Long
  def updateFromTransfer(
              userId: Long, money: BigDecimal, remainingTransfers: Option[Int],
              appliedWildcard: Boolean
            )(implicit c: Connection): Unit
  def getRankings(
                   league: LeagueRow, statFieldId: Long, period: Option[Int], userIds: Option[Array[Long]],
                   secondaryOrdering: Option[List[Long]], showTeam: Boolean
                 )(implicit c: Connection): LeagueRankings
  def getUserStats(
                          leagueId: Long, userId: Option[Long], statFieldId: Option[Long], period: Option[Int],
                          secondaryOrdering: Option[List[Long]],
                          showRanking: Boolean
                        )(implicit c: Connection): Iterable[RankingRow]
  def joinUser(externalUserId: Long, username: String, league: LeagueRow): UserRow
  def userInLeague(externalUserId: Long, leagueId: Long)(implicit c: Connection): Boolean
  def setlateEntryLockTs(userId: Long)(implicit c: Connection): Int
}

@Singleton
class UserRepoImpl @Inject()(db: Database, teamRepo: TeamRepo, pickeeRepo: PickeeRepo)(implicit ec: UserExecutionContext, leagueRepo: LeagueRepo) extends UserRepo{
  private val logger = Logger("application")

  override def update(userId: Long, leagueId: Long, input: UpdateUserFormInput)(implicit c: Connection): Unit = {
    val setString = (input.username, input.externalUserId) match {
      case (Some(username), Some(externalId)) => "set username = {username}, external_user_id = {externalUserId}"
      case (None, Some(externalId)) => "set external_user_id = {externalUserId}"
      case (Some(username), None) => "set username = {username}"
      case (None, None) => ""
    }
    SQL(
      s"update useru $setString where external_user_id = $userId and league_id = $leagueId"
    ).on("username" -> input.username, "externalUserId" -> input.externalUserId).executeUpdate()
    println("todo return stuff")
  }

  override def get(leagueId: Long, externalUserId: Long)
                          (implicit c: Connection): Option[UserRow] = {
    SQL(s"""select user_id, username, external_user_id, money, entered, remaining_transfers, used_wildcard,
            late_entry_lock_ts, eliminated
      from useru where league_id = $leagueId and external_user_id = $externalUserId;""").as(UserRow.parser.singleOpt)
  }

  override def getUsers(userIds: List[Long])(implicit c: Connection): Iterable[UserRow] = {
    // TODO check ANY handling
    SQL"""select user_id, username, external_user_id, money, entered, remaining_transfers, used_wildcard, late_entry_lock_ts, eliminated
      from useru where user_id = ANY(ARRAY[$userIds])""".as(UserRow.parser.*)
  }

  override def getAllUsersForLeague(leagueId: Long)(implicit c: Connection): Iterable[UserRow] = {
    SQL"""select user_id, username, external_user_id, money, entered, remaining_transfers, used_wildcard, late_entry_lock_ts, eliminated
      from useru where league_id = $leagueId""".as(UserRow.parser.*)
  }

  override def insertUser(league: LeagueRow, externalUserId: Long, username: String)(implicit c: Connection): UserRow = {
    println("inserting league user")
    SQL(
      """
        |insert into useru(league_id, external_user_id, username, money, entered, remaining_transfers, used_wildcard) values
        |({leagueId}, {externalUserId}, {username}, {startingMoney}, {entered},
        | {remainingTransfers}, {usedWildcard}) returning user_id;
      """.stripMargin).on(
      "leagueId" -> league.leagueId, "externalUserId" -> externalUserId, "startingMoney" -> league.startingMoney,
      "entered" -> LocalDateTime.now(), "remainingTransfers" -> league.transferLimit, "username" -> username,
      // dont give wildcard to people who join league late
      "usedWildcard" -> (!league.transferWildcard.getOrElse(false) ||
        (leagueRepo.isStarted(league) && league.noWildcardForLateRegister.getOrElse(false)))
    ).executeInsert().get
    println("executed insert league user")
    get(league.leagueId, externalUserId).get
  }

  override def insertUserStat(statFieldId: Long, userId: Long)(implicit c: Connection): Long = {
    SQL(
      s"insert into user_stat(stat_field_id, user_id, previous_rank) VALUES ($statFieldId, $userId, 1) returning user_stat_id;"
    ).executeInsert().get
  }

  override def insertUserStatDaily(userStatId: Long, period: Option[Int])(implicit c: Connection): Long = {
    SQL(
      """insert into user_stat_period(user_stat_id, period, value) VALUES
        |({userStatId}, {period}, 0) returning user_stat_period_id;""".stripMargin
    ).on("userStatId" -> userStatId, "period" -> period).executeInsert().get
  }

  override def updateFromTransfer(
                       userId: Long, money: BigDecimal, remainingTransfers: Option[Int],
                       appliedWildcard: Boolean
                     )(implicit c: Connection): Unit = {
    val usedWildcardSet = if (appliedWildcard) ", used_wildcard = true" else ""
    SQL(s"""update useru set money = {money}, remaining_transfers = {remainingTransfers} $usedWildcardSet where user_id = {userId}""")
      .on("money" -> money, "remainingTransfers" -> remainingTransfers, "userId" -> userId).executeUpdate()
  }

  override def getRankings(
                            league: LeagueRow, statFieldId: Long, period: Option[Int],
                            userIds: Option[Array[Long]], secondaryOrdering: Option[List[Long]], showTeam: Boolean
                          )(implicit c: Connection): LeagueRankings = {
    println(s"getrankings: userIds: ${userIds.map(_.toList.mkString(",")).getOrElse("None")}")
    val qResult = getUserStats(league.leagueId, None, Some(statFieldId), period, secondaryOrdering, true).toList
    val filteredUserRankings = if (userIds.isDefined) qResult.filter(q => userIds.get.toList.contains(q.externalUserId)) else qResult
    LeagueRankings(
      league.leagueId, league.leagueName, leagueRepo.getStatFieldName(statFieldId).get, filteredUserRankings
    )
  }

  override def getUserStats(
                                   leagueId: Long, userId: Option[Long], statFieldId: Option[Long],
                                   period: Option[Int], secondaryOrdering: Option[List[Long]],
                                   showRanking: Boolean
                                 )(implicit c: Connection): Iterable[RankingRow] = {
    // todo assert either league or league user id non empty XOR
    // they are both nullable so that this func can work for either getting all league-users, or just one
    logger.debug("getUserStats")
    // Duplicate rankings not possible as gave it an order by userId
    // as have to use dense ranks because of multi rows for each user
    val rankingParser: RowParser[RankingRow] = Macro.namedParser[RankingRow](ColumnNaming.SnakeCase)
    val periodFilter = if (period.isEmpty) "is null" else s"= ${period.get}"
    val statFieldFilter = if (statFieldId.isEmpty) "" else s"sf.stat_field_id = ${statFieldId.get} and"
    val userFilter = if (userId.isEmpty) "" else s"u.user_id = ${userId.get} and"
    val (extraJoins, extraOrder) = secondaryOrdering match{
      case Some(secondary) => (secondary.map(s =>
        s"""join user_stat us$s on (us$s.user_id = u.user_id and us$s.stat_field_id = $s)
            join user_stat_period usp$s on (usd$s.user_stat_id = us$s.user_stat_id and us$s.period $periodFilter)
            """).mkString(" "),
        secondary.map(s => s"lusd$s.value desc,").mkString(" "))
      case None => ("", "")
    }
    val rankSql = if (showRanking) s"dense_rank() OVER (order by value desc, $extraOrder u.user_id) as ranking" else ""
    val rankSqlOrder = if (showRanking) "ORDER BY ranking" else ""
    val sql = s"""
                 select u.user_id, sf.name as stat_field_name, previous_rank, usp.value, usp.period,
                 $rankSql
                 from useru u join stat_field sf using(league_id)
                 join user_stat us on(sf.stat_field_id = us.stat_field_id and u.user_id = us.user_id)
                 join user_stat_period usp using(user_stat_id)
                 $extraJoins
                 where league_id = $leagueId and $userFilter $statFieldFilter period $periodFilter
                 $rankSqlOrder
      """.stripMargin
    logger.debug(s"sql: $sql")
    SQL(sql).as(RankingRow.parser.*)
  }

  override def joinUser(externalUserId: Long, username: String, league: LeagueRow): UserRow = {
    db.withConnection { implicit c: Connection =>
      println("in joinuser")
      val newUser = insertUser(league, externalUserId, username)
      val newUserStatIds = leagueRepo.getStatFields(league.leagueId).map(
        sf => insertUserStat(sf.statFieldId, newUser.userId)
      )
      newUserStatIds.foreach(sid => insertUserStatDaily(sid, None))

      leagueRepo.getPeriods(league.leagueId).foreach(p =>
        newUserStatIds.foreach(sid => insertUserStatDaily(sid, Some(p.value)))
      )

      if (league.system == "draft"){
        SQL"insert into draft_queue VALUES(${newUser.userId}, ARRAY[]::bigint)".executeInsert()
      }

      newUser
    }
  }

  override def userInLeague(externalUserId: Long, leagueId: Long)(implicit c: Connection): Boolean = {
    SQL(s"select 1 from useru where league_id = $leagueId and external_user_id = $externalUserId").
      as(SqlParser.scalar[Int].singleOpt).isDefined
  }

  override def setlateEntryLockTs(userId: Long)(implicit c: Connection): Int = {
    SQL"""update useru set late_entry_lock_ts = now() where user_id = $userId""".executeUpdate()
  }
}

