package v1.leagueuser

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import org.squeryl.{Query, Table, KeyedEntity}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import scala.util.Try

import models.AppDB._
import models._
import utils.GroupByOrderedImplicit._

import scala.collection.mutable.ArrayBuffer
import v1.team.TeamRepo
import v1.transfer.TransferRepo

case class Ranking(userId: Long, username: String, value: Double, rank: Int, previousRank: Option[Int], team: Option[Iterable[Pickee]])

case class LeagueRankings(leagueId: Long, leagueName: String, statField: String, rankings: Iterable[Ranking])

case class UserHistoricTeamOut(id: Long, externalId: Long, username: String, team: Iterable[Pickee])

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

object UserHistoricTeamOut{
  implicit val implicitWrites = new Writes[UserHistoricTeamOut] {
    def writes(ht: UserHistoricTeamOut): JsValue = {
      Json.obj(
        "id" -> ht.externalId,
        "username" -> ht.username,
        "team" -> ht.team
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


case class DetailedLeagueUser(user: User, leagueUser: LeagueUser, team: Option[List[Pickee]], scheduledTransfers: Option[List[Transfer]], stats: Option[Map[String, Double]])

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
  def getRankings(league: League, statField: LeagueStatField, period: Option[Int], includeTeam: Boolean): LeagueRankings
  def getLeagueUserStats(leagueId: Long, statFieldId: Long, period: Option[Int]): Query[(LeagueUserStat, LeagueUserStatDaily)]
  def getLeagueUserStatsWithUser(leagueId: Long, statFieldId: Long, period: Option[Int]): Query[(User, LeagueUserStat, LeagueUserStatDaily)]
  def leagueUserStatsAndCurrentTeamQuery(leagueId: Long, statFieldId: Long):
    Query[(User, LeagueUserStat, LeagueUserStatDaily, Option[Pickee])]
  def leagueUserStatsAndHistoricTeamQuery(leagueId: Long, statFieldId: Long, period: Int, timestamp: Timestamp):
    Query[(User, LeagueUserStat, LeagueUserStatDaily, Option[Pickee])]
  def getLeagueUserStatsAndTeam(league: League, statFieldId: Long, period: Option[Int], timestamp: Option[Timestamp]):
    Query[(User, LeagueUserStat, LeagueUserStatDaily, Option[Pickee])]
  def getSingleLeagueUserAllStat(leagueUser: LeagueUser, period: Option[Int]): Iterable[(LeagueStatField, LeagueUserStatDaily)]
  def updateLeagueUserStatDaily(newLeagueUserStatsDaily: Iterable[LeagueUserStatDaily])
  def updateLeagueUserStat(newLeagueUserStats: Iterable[LeagueUserStat])
//  def addHistoricTeams(league: League)
//  def addHistoricPickee(team: Iterable[TeamPickee], currentPeriod: Int)
  def getHistoricTeams(league: League, period: Int): Iterable[UserHistoricTeamOut]
  def joinUsers(users: Iterable[User], league: League): Iterable[LeagueUser]
  def userInLeague(userId: Long, leagueId: Long): Boolean
  def getCurrentTeams(leagueId: Long): Iterable[LeagueUserTeamOut]
  def getCurrentTeam(leagueId: Long, userId: Long): LeagueUserTeamOut

  //private def statFieldIdFromName(statFieldName: String, leagueId: Long)
}

@Singleton
class LeagueUserRepoImpl @Inject()(transferRepo: TransferRepo, teamRepo: TeamRepo)(implicit ec: LeagueExecutionContext) extends LeagueUserRepo{

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
        Some(teamRepo.getLeagueUserTeam(leagueUser).flatMap(_._2))
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
      (!league.transferWildcard || league.started)
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

  override def getRankings(league: League, statField: LeagueStatField, period: Option[Int], includeTeam: Boolean): LeagueRankings = {
    val rankings = includeTeam match{
      case false => {
        val stats = this.getLeagueUserStatsWithUser(league.id, statField.id, period)
        var lastScore = Double.MaxValue // TODO java max num
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
        val stats = this.getLeagueUserStatsAndTeam(league, statField.id, period, None).toList.groupByOrdered(_._1).toList
        var lastScore = Double.MaxValue // TODO java max num
        var lastScoreRank = 0
        val tmp = stats.map({case (u, v) => {
          val team = v.flatMap(_._4)
          (v.head, team)}
        })
        tmp.zipWithIndex.map({case ((q, team), i) => {
          println(f"i: $i")
          val value = q._3.value
          println(f"value: $value")
          val rank = if (value == lastScore) lastScoreRank else i + 1
          println(f"rank: $rank")
          lastScore = value
          lastScoreRank = rank
          val previousRank = period match {
            // Previous rank explicitly means overall ranking at end of last period
            // so doesnt make sense to show/associate it with singular period ranking
            case None => Some(q._2.previousRank)
            case Some(_) => None
          }
          Ranking(q._1.id, q._1.username, value, rank, previousRank, Some(team))
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
      leagueUserTable, leagueUserStatTable, leagueUserStatDailyTable, transferTable
    )((lu, lus, s, t) =>
      where(
        lus.leagueUserId === lu.id and s.leagueUserStatId === lus.id and
          lu.leagueId === leagueId and lus.statFieldId === statFieldId and s.period === period and t.leagueUserId === lu.id
      )
        select (lus, s)
        orderBy (s.value desc)
    )
  }

  override def getSingleLeagueUserAllStat(leagueUser: LeagueUser, period: Option[Int]): Iterable[(LeagueStatField, LeagueUserStatDaily)] = {
    // TODO cross joins go weird?
    val q = join(
      leagueStatFieldTable, leagueUserStatTable.leftOuter, leagueUserStatDailyTable.leftOuter
    )((lsf, lus, s) =>
      where(
        lus.get.leagueUserId === leagueUser.id and lsf.leagueId === leagueUser.leagueId and s.get.period === period
      )
        select (lsf, s.get)
        on(lsf.id === lus.map(_.statFieldId), s.map(_.leagueUserStatId) === lus.map(_.id))
    ).toList
    //println(q.mkString(", "))
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

  override def leagueUserStatsAndCurrentTeamQuery(leagueId: Long, statFieldId: Long):
    Query[(User, LeagueUserStat, LeagueUserStatDaily, Option[Pickee])] = {
      join (
        userTable, leagueUserTable, leagueUserStatTable, leagueUserStatDailyTable, teamTable.leftOuter,
        teamPickeeTable.leftOuter, pickeeTable.leftOuter, transferTable
      ) ((u, lu, lus, s, team, tp, p, t) =>
        where (
          lu.leagueId === leagueId and lus.statFieldId === statFieldId and s.period.isNull and team.map(_.ended.isEmpty) === true
        )
          select ((u, lus, s, p))
          orderBy (s.value desc)
          on (
          lu.userId === u.id, lus.leagueUserId === lu.id, s.leagueUserStatId === lus.id,
          team.map(_.leagueUserId) === lu.id, tp.map(_.teamId) === team.map(_.id), tp.map (_.pickeeId) === p.map (_.id), t.leagueUserId === lu.id)
      )
  }

  override def leagueUserStatsAndHistoricTeamQuery(leagueId: Long, statFieldId: Long, period: Int, timestamp: Timestamp):
  Query[(User, LeagueUserStat, LeagueUserStatDaily, Option[Pickee])] = {
    join (
      userTable, leagueUserTable, leagueUserStatTable, leagueUserStatDailyTable, teamTable.leftOuter,
      teamPickeeTable.leftOuter, pickeeTable.leftOuter, transferTable
    ) ((u, lu, lus, s, team, tp, p, t) =>
      where (
        lu.leagueId === leagueId and lus.statFieldId === statFieldId and s.period === Some(period)
        and team.map(_.started).map(_ < timestamp) and team.map(_.ended).map(_ >= timestamp)
      )
        select ((u, lus, s, p) )
        orderBy (s.value desc)
        on (
        lu.userId === u.id, lus.leagueUserId === lu.id, s.leagueUserStatId === lus.id,
        team.map(_.leagueUserId) === lu.id, tp.map(_.teamId) === team.map(_.id), tp.map(_.pickeeId) === p.map (_.id), t.leagueUserId === lu.id)
    )
  }

  override def getLeagueUserStatsAndTeam(league: League, statFieldId: Long, period: Option[Int], timestamp: Option[Timestamp]):
    Query[(User, LeagueUserStat, LeagueUserStatDaily, Option[Pickee])] = {
    // TODO what about current day?
    // squeryl optionally do tp map if period exists
    // TODO add new timestamp field to transfer? so in future can construct team at any time just from transfers table
    // cannot dynamically specify table or will not compile
    (period, league.currentPeriod, timestamp) match {
      case (None, _, None) => this.leagueUserStatsAndCurrentTeamQuery(league.id, statFieldId)
      case (_, None, _) => this.leagueUserStatsAndCurrentTeamQuery(league.id, statFieldId)
      case (Some(periodVal), Some(currentPeriod), None) if periodVal == currentPeriod.value =>
        this.leagueUserStatsAndCurrentTeamQuery(league.id, statFieldId)
      case (Some(_), _, Some(_)) => throw new Exception("Specify period, or timestamp. not both")
      case (None, _, Some(t)) => {
        val period = from(periodTable)(
          p => where(p.start > t and p.leagueId === league.id and p.end <= t)
            select p
        ).single
        this.leagueUserStatsAndHistoricTeamQuery(league.id, statFieldId, period.value, t)
      }
      case (Some(pVal), _, None) => {
        val endPeriodTstamp = from(periodTable)(
          p => where(p.value === pVal and p.leagueId === league.id)
          select p
        ).single.end
        this.leagueUserStatsAndHistoricTeamQuery(league.id, statFieldId, pVal, endPeriodTstamp)
      }
    }
  }

  override def updateLeagueUserStatDaily(newLeagueUserStatsDaily: Iterable[LeagueUserStatDaily]): Unit = {
    leagueUserStatDailyTable.update(newLeagueUserStatsDaily)
  }

  override def updateLeagueUserStat(newLeagueUserStats: Iterable[LeagueUserStat]): Unit = {
    leagueUserStatTable.update(newLeagueUserStats)
  }

  override def getHistoricTeams(league: League, period: Int): Iterable[UserHistoricTeamOut] = {
    from(historicTeamPickeeTable, leagueUserTable, leagueTable, userTable, pickeeTable)(
      (h, lu, l, u, p) => where(h.leagueUserId === lu.id and lu.leagueId === league.id and h.period === period and u.id === lu.userId and h.pickeeId === p.id)
        select ((p, u))
        ).groupBy(_._2).map({case (user, v) => {
          UserHistoricTeamOut(user.id, user.externalId, user.username, v.map(_._1))
        }})
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

  override def getCurrentTeams(leagueId: Long): Iterable[LeagueUserTeamOut] = {
    join(leagueUserTable, teamTable.leftOuter, teamPickeeTable.leftOuter, pickeeTable.leftOuter)((lu, team, tp, p) =>
          where(lu.leagueId === leagueId)
          select((lu, p))
          on(lu.id === team.map(_.leagueUserId), team.map(_.id) === tp.map(_.teamId), tp.map(_.pickeeId) === p.map(_.id))
          ).groupBy(_._1).map({case (leagueUser, v) => {
            LeagueUserTeamOut(leagueUser, v.flatMap(_._2))
          }})
  }

  override def getCurrentTeam(leagueId: Long, userId: Long): LeagueUserTeamOut = {
    val query = join(leagueUserTable, teamTable.leftOuter, teamPickeeTable.leftOuter, pickeeTable.leftOuter)((lu, team, tp, p) =>
        where(lu.leagueId === leagueId and lu.userId === userId)
        select((lu, p))
        on(lu.id === team.map(_.leagueUserId), team.map(_.id) === tp.map(_.teamId), tp.map(_.pickeeId) === p.map(_.id))
        )
    val leagueUser = query.head._1
    val team = query.flatMap(_._2)
    LeagueUserTeamOut(leagueUser, team)
  }
}

