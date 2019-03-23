package v1.leagueuser

import java.sql.{Timestamp, Connection}
//import java.math.BigDecimal
import java.sql.{Connection, Timestamp}

import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import org.squeryl.{KeyedEntity, Query, Table}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import play.api.db._
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

import scala.util.Try
import models._
import org.squeryl.dsl.ast.TrueLogicalBoolean
import utils.GroupByOrderedImplicit._

import scala.collection.mutable.ArrayBuffer
import v1.team.TeamRepo
import v1.transfer.TransferRepo

case class PickeeRow(pickeeId: Long, name: String, cost: BigDecimal)

object PickeeRow {
  implicit val implicitWrites = new Writes[PickeeRow] {
    def writes(x: PickeeRow): JsValue = {
      Json.obj(
        "id" -> x.pickeeId,
        "name" -> x.name,
        "cost" -> x.cost
      )
    }
  }
}

case class Ranking(userId: Long, username: String, value: Double, rank: Int, previousRank: Option[Int], team: Option[Iterable[PickeeRow]])

case class LeagueRankings(leagueId: Long, leagueName: String, statField: String, rankings: Iterable[Ranking])

case class UserWithLeagueUser(user: User, info: LeagueUser)

case class LeagueWithLeagueUser(league: League, info: LeagueUser)

object UserWithLeagueUser {
  implicit val implicitWrites = new Writes[UserWithLeagueUser] {
    def writes(x: UserWithLeagueUser): JsValue = {
      Json.obj(
        "user" -> x.user,
        "leagueInfo" -> x.info
      )
    }
  }
}

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

case class LeagueUserTeamOut(leagueUser: LeagueUser, team: Iterable[Pickee])
object LeagueUserTeamOut{
  implicit val implicitWrites = new Writes[LeagueUserTeamOut] {
    def writes(x: LeagueUserTeamOut): JsValue = {
      Json.obj(
        "leagueUser" -> x.leagueUser,
        "team" -> x.team,
      )
    }
  }
}


case class DetailedLeagueUser(user: User, leagueUser: LeagueUser, team: Option[List[PickeeRow]], scheduledTransfers: Option[List[Transfer]], stats: Option[Map[String, Double]])

object DetailedLeagueUser{
  implicit val implicitWrites = new Writes[DetailedLeagueUser] {
    def writes(x: DetailedLeagueUser): JsValue = {
      Json.obj(
        "user" -> x.user,
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
  def getUserWithLeagueUser(leagueId: Long, userId: Long, externalId: Boolean): UserWithLeagueUser
  def combineUserLeagueUser(user: User, leagueUser: LeagueUser): UserWithLeagueUser
  def detailedLeagueUser(user: User, leagueUser: LeagueUser, showTeam: Boolean, showScheduledTransfers: Boolean, stats: Boolean): DetailedLeagueUser
  def getAllLeaguesForUser(userId: Long): Iterable[LeagueWithLeagueUser]
  def getAllUsersForLeague(leagueId: Long): Iterable[UserWithLeagueUser]
  def insertLeagueUser(league: League, userId: Long): LeagueUser
  def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long): LeagueUserStat
  def insertLeagueUserStatDaily(leagueUserStatId: Long, period: Option[Int]): LeagueUserStatDaily
  def getStatField(leagueId: Long, statFieldName: String): Option[LeagueStatField]
  def getRankings(
                   league: League, statField: LeagueStatField, period: Option[Int], includeTeam: Boolean, userIds: Option[Array[Long]],
                   secondaryOrdering: Option[List[Long]]
                 )(implicit c: Connection): LeagueRankings
  def getLeagueUserStats(leagueId: Long, statFieldId: Long, period: Option[Int]): Query[(LeagueUserStat, LeagueUserStatDaily)]
  def getLeagueUserStatsWithUser(leagueId: Long, statFieldId: Long, period: Option[Int]): Query[(User, LeagueUserStat, LeagueUserStatDaily)]
  def leagueUserStatsAndTeamQuery(leagueId: Long, statFieldId: Long, period: Option[Int],
                                   timestamp: Option[Timestamp], secondaryOrdering: Option[List[Long]])(implicit c: Connection):
    Iterable[RankingRow]
  def getLeagueUserStatsAndTeam(league: League, statFieldId: Long, period: Option[Int], timestamp: Option[Timestamp], secondaryOrdering: Option[List[Long]])(implicit c: Connection):
  Iterable[RankingRow]
  def getSingleLeagueUserAllStat(leagueUser: LeagueUser, period: Option[Int]): Iterable[(LeagueStatField, LeagueUserStatDaily)]
  def updateLeagueUserStatDaily(newLeagueUserStatsDaily: Iterable[LeagueUserStatDaily])
  def updateLeagueUserStat(newLeagueUserStats: Iterable[LeagueUserStat])
  def getHistoricTeams(league: League, period: Int): Iterable[LeagueUserTeamOut]
  def joinUsers(users: Iterable[User], league: League): Iterable[LeagueUser]
  def userInLeague(userId: Long, leagueId: Long): Boolean
  def getCurrentTeams(leagueId: Long): Iterable[LeagueUserTeamOut]
  def getCurrentTeam(leagueId: Long, userId: Long): LeagueUserTeamOut

  //private def statFieldIdFromName(statFieldName: String, leagueId: Long)
}

@Singleton
class LeagueUserRepoImpl @Inject()(db: Database, transferRepo: TransferRepo, teamRepo: TeamRepo)(implicit ec: LeagueExecutionContext) extends LeagueUserRepo{

  override def getUserWithLeagueUser(leagueId: Long, userId: Long, externalId: Boolean): UserWithLeagueUser = {
    // helpful for way squeryl deals with optional filters
    val (internalUserId, externalUserId) = externalId match {
      case true => (None, Some(userId))
      case false => (Some(userId), None)
    }
    val query =from(userTable, leagueUserTable)((u, lu) => where(lu.leagueId === leagueId and (u.externalId === externalUserId) and (u.id === internalUserId))
      select(u, lu))
        .single
    UserWithLeagueUser(query._1, query._2)
  }

  override def combineUserLeagueUser(user: User, leagueUser: LeagueUser): UserWithLeagueUser = UserWithLeagueUser(user, leagueUser)

  override def detailedLeagueUser(user: User, leagueUser: LeagueUser, showTeam: Boolean, showScheduledTransfers: Boolean, showStats: Boolean): DetailedLeagueUser = {
    //TODO use squeryls dynamic queries
    val team = showTeam match{
      case false => None
      case true => {
        db.withConnection { implicit c =>
          Some(teamRepo.getLeagueUserTeam(leagueUser))
        }
      }
    }
    // todo boolean to option?
    val scheduledTransfers = showScheduledTransfers match{
      case false => None
      case true => {
        Some(transferRepo.getLeagueUserTransfer(leagueUser, Some(true)))
      }
    }
    val stats = showStats match{
      case false => None
      case true => {
        Some(getSingleLeagueUserAllStat(leagueUser, None).map(q => q._1.name -> q._2.value).toMap)
      }
    }
    DetailedLeagueUser(user, leagueUser, team, scheduledTransfers, stats)
  }

  override def getAllLeaguesForUser(userId: Long): Iterable[LeagueWithLeagueUser] = {
    from(leagueTable, userTable, leagueUserTable)((l, u, lu) => 
          where(u.id === userId and lu.userId === u.id and lu.leagueId === l.id)
          select(l, lu)
          ).map(q => LeagueWithLeagueUser(q._1, q._2))
  }
  override def getAllUsersForLeague(leagueId: Long): Iterable[UserWithLeagueUser] = {
    from(leagueTable, userTable, leagueUserTable)((l, u, lu) => 
          where(l.id === leagueId and lu.userId === u.id and lu.leagueId === l.id)
          select(u, lu)
          ).map(q => UserWithLeagueUser(q._1, q._2))
  }

  override def insertLeagueUser(league: League, userId: Long): LeagueUser = {
    // dont give wildcard to people who join league late
    leagueUserTable.insert(new LeagueUser(
      league.id, userId, league.startingMoney, new Timestamp(System.currentTimeMillis()), league.transferLimit,
      !league.transferWildcard || (league.started && league.noWildcardForLateRegister)
    ))
  }

  override def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long): LeagueUserStat = {
    leagueUserStatTable.insert(new LeagueUserStat(
      statFieldId, leagueUserId
    ))
  }

  override def insertLeagueUserStatDaily(leagueUserStatId: Long, period: Option[Int]): LeagueUserStatDaily = {
    leagueUserStatDailyTable.insert(new LeagueUserStatDaily(
      leagueUserStatId, period
    ))
  }

  override def getStatField(leagueId: Long, statFieldName: String): Option[LeagueStatField] = {
    Try(leagueStatFieldTable.where(
      lsf => lsf.leagueId === leagueId and lower(lsf.name) === statFieldName.toLowerCase()
    ).single).toOption
  }

  override def getRankings(
                            league: League, statField: LeagueStatField, period: Option[Int], includeTeam: Boolean,
                            userIds: Option[Array[Long]], secondaryOrdering: Option[List[Long]]
                          )(implicit c: Connection): LeagueRankings = {
    val rankings = includeTeam match{
      case false => {
        // TODO I HAVE NEGLECTED THE FUCK OUT OF THID BRANCH
        val stats = this.getLeagueUserStatsWithUser(league.id, statField.id, period)
        var lastScore = Double.MaxValue
        var lastScoreRank = 0
        stats.zipWithIndex.map({case (q, i) => {
          val value = q._3.value
          val rank = if (value == lastScore) lastScoreRank else i + 1
          lastScore = value
          lastScoreRank = rank
          val previousRank = period match {
            // Previous rank explicitly means overall ranking at end of last period
            // so doesnt make sense to show/associate it with singular period ranking
            case None => Some(q._2.previousRank)
            case Some(_) => None
          }
          Ranking(q._1.id, q._1.username, value, rank, previousRank, None)
        }})}
      case true => {
        println(s"getrankings: userIds: ${userIds.map(_.toList.mkString(",")).getOrElse("None")}")
        val qResult = this.getLeagueUserStatsAndTeam(league, statField.id, period, None, secondaryOrdering).toList
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
//          val previousRank = period match {
//            // Previous rank explicitly means overall ranking at end of last period
//            // so doesnt make sense to show/associate it with singular period ranking
//            case None => q.previousRank
//            case Some(_) => None
//          }
          Ranking(q.userId, q.username, value, rank, q.previousRank, team)
        }})
      }
    }

    LeagueRankings(
      league.id, league.name, statField.name, rankings
    )
  }
  override def getLeagueUserStats(
                                  leagueId: Long, statFieldId: Long, period: Option[Int]
                                ): Query[(LeagueUserStat, LeagueUserStatDaily)] = {
    from(
      // inner join on transfer table so we dont rank users who never made a team
      leagueUserTable, leagueUserStatTable, leagueUserStatDailyTable
    )((lu, lus, s) =>
      where(
        lus.leagueUserId === lu.id and s.leagueUserStatId === lus.id and
          lu.leagueId === leagueId and lus.statFieldId === statFieldId and s.period === period
        and (lu.id in from(teamTable)(t => select(t.leagueUserId)))
      )
        select (lus, s)
        orderBy (s.value desc)
    )
  }

  override def getSingleLeagueUserAllStat(leagueUser: LeagueUser, period: Option[Int]): Iterable[(LeagueStatField, LeagueUserStatDaily)] = {
    val q = join(
      leagueStatFieldTable, leagueUserStatTable.leftOuter, leagueUserStatDailyTable.leftOuter
    )((lsf, lus, s) =>
      where(
        lus.get.leagueUserId === leagueUser.id and lsf.leagueId === leagueUser.leagueId and s.get.period === period
      )
        select (lsf, s.get)
        on(lsf.id === lus.map(_.statFieldId), s.map(_.leagueUserStatId) === lus.map(_.id))
    ).toList
    q
  }

  override def getLeagueUserStatsWithUser(
                                  leagueId: Long, statFieldId: Long, period: Option[Int]
                                ): Query[(User, LeagueUserStat, LeagueUserStatDaily)] = {
      from(
        userTable, leagueUserTable, leagueUserStatTable, leagueUserStatDailyTable, transferTable
      )((u, lu, lus, s, t) =>
        where(
          lu.userId === u.id and lus.leagueUserId === lu.id and s.leagueUserStatId === lus.id and
            lu.leagueId === leagueId and lus.statFieldId === statFieldId and s.period === period and t.leagueUserId === lu.id
        )
          select ((u, lus, s))
          orderBy (s.value desc)
      )
  }

  override def leagueUserStatsAndTeamQuery(leagueId: Long, statFieldId: Long, period: Option[Int],
                                                   timestamp: Option[Timestamp], secondaryOrdering: Option[List[Long]])(implicit c: Connection): Iterable[RankingRow] = {
    val rankingParser: RowParser[RankingRow] = Macro.namedParser[RankingRow](ColumnNaming.SnakeCase)
    println(timestamp)
    println(period)
    val timestampFilter = if (timestamp.isDefined) "t.timespan @> {timestamp}::timestamptz" else "upper(t.timespan) is NULL"
    val periodFilter = if (period.isDefined) "lusd.period = {period}" else "lusd.period is NULL"
    // TODO nice injection
    val q = secondaryOrdering match {
      case None => s"""select u.external_id as user_id, u.username, lusd.value, lus.previous_rank, tp.pickee_id, p.name as pickee_name, p.cost from useru u
           join league_user lu on (u.id = lu.user_id)
           join team t on (t.league_user_id = lu.id and $timestampFilter)
           left join team_pickee tp on (tp.team_id = t.id)
           left join pickee p on (p.id = tp.pickee_id)
           join league_user_stat lus on (lus.league_user_id = lu.id and lus.stat_field_id = {statFieldId})
           join league_user_stat_daily lusd on (lusd.league_user_stat_id = lus.id and $periodFilter)
           order by lusd.value desc;
           """
      case Some(secondary) => {
        val extraJoins = secondary.map (s =>
        s"""join league_user_stat lus$s on (lus$s.league_user_id = lu.id and lus$s.stat_field_id = $s)
            join league_user_stat_daily lusd$s on (lusd$s.league_user_stat_id = lus$s.id and $periodFilter)
            """).mkString(" ")
        val extraOrder = secondary.map (s => s"lusd$s.value desc").mkString(" ")
        s"""select u.external_id as user_id, u.username, lusd.value, lus.previous_rank, tp.pickee_id, p.name as pickee_name, p.cost from useru u
             join league_user lu on (u.id = lu.user_id)
             join team t on (t.league_user_id = lu.id and $timestampFilter)
             left join team_pickee tp on (tp.team_id = t.id)
             left join pickee p on (p.id = tp.pickee_id)
             join league_user_stat lus on (lus.league_user_id = lu.id and lus.stat_field_id = {statFieldId})
             join league_user_stat_daily lusd on (lusd.league_user_stat_id = lus.id and $periodFilter)
             $extraJoins
             order by lusd.value desc $extraOrder;
             """
      }
    }
    println(q)
    SQL(q).on ("timestamp" -> timestamp, "period" -> period, "statFieldId" -> statFieldId).as(rankingParser.*)
  }

  override def getLeagueUserStatsAndTeam(
                                          league: League, statFieldId: Long, period: Option[Int],
                                          timestamp: Option[Timestamp], secondaryOrdering: Option[List[Long]]
                                        )(implicit c: Connection):
    Iterable[RankingRow] = {
    // hahaha. rofllwefikl!s
    (period, league.currentPeriod, timestamp) match {
      case (None, _, None) => this.leagueUserStatsAndTeamQuery(league.id, statFieldId, None, None, secondaryOrdering)
      case (_, None, _) => this.leagueUserStatsAndTeamQuery(league.id, statFieldId, None, None, secondaryOrdering)
      case (Some(periodVal), Some(currentPeriod), None) if periodVal == currentPeriod.value =>
        this.leagueUserStatsAndTeamQuery(league.id, statFieldId, Some(periodVal), None,secondaryOrdering)
      case (Some(_), _, Some(_)) => throw new Exception("Specify period, or timestamp. not both")
      case (None, _, Some(t)) => {
        val period = from(periodTable)(
          p => where(p.start > t and p.leagueId === league.id and p.end <= t)
            select p
        ).single
        this.leagueUserStatsAndTeamQuery(league.id, statFieldId, Some(period.value), Some(t), secondaryOrdering)
      }
      case (Some(pVal), _, None) => {
        println("cat")
        val endPeriodTstamp = from(periodTable)(
          p => where(p.value === pVal and p.leagueId === league.id)
          select p
        ).single.end
        this.leagueUserStatsAndTeamQuery(league.id, statFieldId, Some(pVal), Some(endPeriodTstamp), secondaryOrdering)
      }
    }
  }

  override def updateLeagueUserStatDaily(newLeagueUserStatsDaily: Iterable[LeagueUserStatDaily]): Unit = {
    leagueUserStatDailyTable.update(newLeagueUserStatsDaily)
  }

  override def updateLeagueUserStat(newLeagueUserStats: Iterable[LeagueUserStat]): Unit = {
    leagueUserStatTable.update(newLeagueUserStats)
  }

  override def joinUsers(users: Iterable[User], league: League): Iterable[LeagueUser] = {
    // TODO move to league user repo
    // // can ust pass stat field ids?
    val newLeagueUsers = users.map(u => insertLeagueUser(league, u.id))
    val newLeagueUserStats = league.statFields.flatMap(sf => newLeagueUsers.map(nlu => insertLeagueUserStat(sf.id, nlu.id)))

    newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.id, None))

    league.periods.foreach(p =>
      newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.id, Some(p.value)))
    )

    newLeagueUsers
  }

  override def userInLeague(userId: Long, leagueId: Long): Boolean = {
    from(leagueUserTable)(lu => where(lu.leagueId === leagueId and lu.userId === userId).select(1)).nonEmpty
  }

  override def getCurrentTeams(leagueId: Long):  = {
    1
//    join(leagueUserTable, teamTable.leftOuter, teamPickeeTable.leftOuter, pickeeTable.leftOuter)((lu, team, tp, p) =>
//          where(lu.leagueId === leagueId)
//          select((lu, p))
//          on(lu.id === team.map(_.leagueUserId), team.map(_.id) === tp.map(_.teamId), tp.map(_.pickeeId) === p.map(_.id))
//          ).groupBy(_._1).map({case (leagueUser, v) => {
//            LeagueUserTeamOut(leagueUser, v.flatMap(_._2))
//          }})
  }

  override def getCurrentTeam(leagueId: Long, userId: Long): int = {
    1
//    val query = join(leagueUserTable, teamTable.leftOuter, teamPickeeTable.leftOuter, pickeeTable.leftOuter)((lu, team, tp, p) =>
//        where(lu.leagueId === leagueId and lu.userId === userId)
//        select((lu, p))
//        on(lu.id === team.map(_.leagueUserId), team.map(_.id) === tp.map(_.teamId), tp.map(_.pickeeId) === p.map(_.id))
//        )
//    val leagueUser = query.head._1
//    val team = query.flatMap(_._2)
//    LeagueUserTeamOut(leagueUser, team)
  }
}

