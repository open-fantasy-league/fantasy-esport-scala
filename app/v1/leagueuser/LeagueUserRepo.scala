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
import utils.CostConverter

import scala.collection.mutable.ArrayBuffer

case class Ranking(userId: Long, username: String, value: Double, rank: Int, previousRank: Option[Int])

case class LeagueRankings(leagueId: Long, leagueName: String, statField: String, rankings: Iterable[Ranking])

case class UserHistoricTeamOut(id: Long, externalId: Option[Long], username: String, team: Iterable[Pickee])

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
        "id" -> ht.id,
        "externalId" -> ht.externalId,
        "username" -> ht.username,
        "team" -> ht.team
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
        "previousRank" -> ranking.previousRank
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

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait LeagueUserRepo{
  def getLeagueUser(leagueId: Long, userId: Long): LeagueUser
  def getAllLeaguesForUser(userId: Long): Iterable[LeagueWithLeagueUser]
  def getAllUsersForLeague(leagueId: Long): Iterable[UserWithLeagueUser]
  def insertLeagueUser(league: League, userId: Long): LeagueUser
  def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long): LeagueUserStat
  def insertLeagueUserStatDaily(leagueUserStatId: Long, period: Option[Int]): LeagueUserStatDaily
  def getStatField(leagueId: Long, statFieldName: String): Option[LeagueStatField]
  def getRankings(league: League, statField: LeagueStatField, period: Option[Int]): LeagueRankings
  def getLeagueUserStat(leagueId: Long, statFieldId: Long, period: Option[Int]): Query[(LeagueUserStat, LeagueUserStatDaily)]
  def getLeagueUserStatWithUser(leagueId: Long, statFieldId: Long, period: Option[Int]): Query[(User, LeagueUserStat, LeagueUserStatDaily)]
  def updateLeagueUserStatDaily(newLeagueUserStatsDaily: Iterable[LeagueUserStatDaily])
  def updateLeagueUserStat(newLeagueUserStats: Iterable[LeagueUserStat])
  def addHistoricTeams(league: League)
  def addHistoricPickee(team: Iterable[TeamPickee], currentPeriod: Int)
  def getHistoricTeams(league: League, period: Int): Iterable[UserHistoricTeamOut]
  def joinUsers(users: Iterable[User], league: League)
  def userInLeague(userId: Long, leagueId: Long): Boolean
  def getCurrentTeams(leagueId: Long): Iterable[LeagueUserTeamOut]
  def getCurrentTeam(leagueId: Long, userId: Long): LeagueUserTeamOut

  //private def statFieldIdFromName(statFieldName: String, leagueId: Long)
}

@Singleton
class LeagueUserRepoImpl @Inject()()(implicit ec: LeagueExecutionContext) extends LeagueUserRepo{

  override def getLeagueUser(leagueId: Long, userId: Long): LeagueUser = {
    from(leagueUserTable)(lu => where(lu.leagueId === leagueId and lu.userId === userId)
      select lu)
        .single
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
    leagueUserTable.insert(new LeagueUser(
      league.id, userId, league.startingMoney, new Timestamp(System.currentTimeMillis()), league.transferLimit,
      !league.transferWildcard
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

  override def getRankings(league: League, statField: LeagueStatField, period: Option[Int]): LeagueRankings = {
    val stats = this.getLeagueUserStatWithUser(league.id, statField.id, period)
    var lastScore = Double.MaxValue // TODO java max num
    var lastScoreRank = 0
    val rankings = stats.zipWithIndex.map({case (q, i) => {
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
      Ranking(q._1.id, q._1.username, value, rank, previousRank)
    }})
    LeagueRankings(
      league.id, league.name, statField.name, rankings
    )
  }
  override def getLeagueUserStat(
                                  leagueId: Long, statFieldId: Long, period: Option[Int]
                                ): Query[(LeagueUserStat, LeagueUserStatDaily)] = {
    from(
      leagueUserTable, leagueUserStatTable, leagueUserStatDailyTable
    )((lu, lus, s) =>
      where(
        lus.leagueUserId === lu.id and s.leagueUserStatId === lus.id and
          lu.leagueId === leagueId and lus.statFieldId === statFieldId and s.period === period
      )
        select (lus, s)
        orderBy (s.value desc)
    )
  }

  override def getLeagueUserStatWithUser(
                                  leagueId: Long, statFieldId: Long, period: Option[Int]
                                ): Query[(User, LeagueUserStat, LeagueUserStatDaily)] = {
      println(s"period: ${period}")
      from(
        userTable, leagueUserTable, leagueUserStatTable, leagueUserStatDailyTable
      )((u, lu, lus, s) =>
        where(
          lu.userId === u.id and lus.leagueUserId === lu.id and s.leagueUserStatId === lus.id and
            lu.leagueId === leagueId and lus.statFieldId === statFieldId and s.period === period
        )
          select ((u, lus, s))
          orderBy (s.value desc)
      )
  }

  override def updateLeagueUserStatDaily(newLeagueUserStatsDaily: Iterable[LeagueUserStatDaily]): Unit = {
    leagueUserStatDailyTable.update(newLeagueUserStatsDaily)
  }

  override def updateLeagueUserStat(newLeagueUserStats: Iterable[LeagueUserStat]): Unit = {
    leagueUserStatTable.update(newLeagueUserStats)
  }

  override def addHistoricTeams(league: League): Unit ={
    (for{
      _ <- league.users.associations.map(_.team).map(addHistoricPickee(_, league.currentPeriod.getOrElse(new Period()).value))
    } yield None)
  }

  override def addHistoricPickee(team: Iterable[TeamPickee], currentPeriod: Int) = {
    historicTeamPickeeTable.insert(team.map(t => new HistoricTeamPickee(t.pickeeId, t.leagueUserId, currentPeriod)))
  }

  override def getHistoricTeams(league: League, period: Int): Iterable[UserHistoricTeamOut] = {
    from(historicTeamPickeeTable, leagueUserTable, leagueTable, userTable, pickeeTable)(
      (h, lu, l, u, p) => where(h.leagueUserId === lu.id and lu.leagueId === league.id and h.period === period and u.id === lu.userId and h.pickeeId === p.id)
        select ((p, u))
        ).groupBy(_._2).map({case (user, v) => {
          UserHistoricTeamOut(user.id, user.externalId, user.username, v.map(_._1))
        }})
  }

  override def joinUsers(users: Iterable[User], league: League) = {
    // TODO move to league user repo
    // // can ust pass stat field ids?
    val newLeagueUsers = users.map(u => insertLeagueUser(league, u.id))
    val newLeagueUserStats = league.statFields.flatMap(sf => newLeagueUsers.map(nlu => insertLeagueUserStat(sf.id, nlu.id)))

    newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.id, None))

    league.periods.foreach(p =>
      newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.id, Some(p.value)))
    )
  }

  override def userInLeague(userId: Long, leagueId: Long): Boolean = {
    !from(leagueUserTable)(lu => where(lu.leagueId === leagueId and lu.userId === userId).select(1)).isEmpty
  }

  override def getCurrentTeams(leagueId: Long): Iterable[LeagueUserTeamOut] = {
    join(leagueUserTable, teamPickeeTable.leftOuter, pickeeTable.leftOuter)((lu, tp, p) =>
          where(lu.leagueId === leagueId)
          select((lu, p))
          on(lu.id === tp.map(_.leagueUserId), tp.map(_.pickeeId) === p.map(_.id))
          ).groupBy(_._1).map({case (leagueUser, v) => {
            LeagueUserTeamOut(leagueUser, v.flatMap(_._2))
          }})
  }

  override def getCurrentTeam(leagueId: Long, userId: Long): LeagueUserTeamOut = {
    val query = join(leagueUserTable, teamPickeeTable.leftOuter, pickeeTable.leftOuter)((lu, tp, p) => 
        where(lu.leagueId === leagueId and lu.userId === userId)
        select((lu, p))
        on(lu.id === tp.map(_.leagueUserId), tp.map(_.pickeeId) === p.map(_.id))
        )
    val leagueUser = query.head._1
    val team = query.flatMap(_._2)
    LeagueUserTeamOut(leagueUser, team)
  }
}

