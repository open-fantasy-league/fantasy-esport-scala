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

case class Ranking(userId: Int, username: String, value: Double, rank: Int, previousRank: Option[Int])

case class LeagueRankings(leagueId: Int, leagueName: String, statField: String, rankings: Iterable[Ranking])

case class UserHistoricTeamOut(id: Int, externalId: Option[Long], username: String, team: Iterable[Pickee])

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

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait LeagueUserRepo{
  def getLeagueUser(leagueId: Int, userId: Int): LeagueUser
  def getAllLeaguesForUser(userId: Int): Iterable[LeagueWithLeagueUser]
  def getAllUsersForLeague(leagueId: Int): Iterable[UserWithLeagueUser]
  def insertLeagueUser(league: League, userId: Int): LeagueUser
  def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long): LeagueUserStat
  def insertLeagueUserStatDaily(leagueUserStatId: Long, day: Option[Int]): LeagueUserStatDaily
  def getStatField(leagueId: Int, statFieldName: String): Option[LeagueStatField]
  def getRankings(league: League, statField: LeagueStatField, day: Option[Int]): LeagueRankings
  def getLeagueUserStat(leagueId: Int, statFieldId: Long, day: Option[Int]): Query[(LeagueUserStat, LeagueUserStatDaily)]
  def getLeagueUserStatWithUser(leagueId: Int, statFieldId: Long, day: Option[Int]): Query[(User, LeagueUserStat, LeagueUserStatDaily)]
  def updateLeagueUserStatDaily(newLeagueUserStatsDaily: Iterable[LeagueUserStatDaily])
  def updateLeagueUserStat(newLeagueUserStats: Iterable[LeagueUserStat])
  def addHistoricTeams(league: League)
  def addHistoricPickee(team: Iterable[TeamPickee], currentPeriod: Int)
  def getHistoricTeams(league: League, day: Int): Iterable[UserHistoricTeamOut]
  def joinUsers(users: Iterable[User], league: League, statFields: Iterable[LeagueStatField], periods: Iterable[Period])

  //private def statFieldIdFromName(statFieldName: String, leagueId: Int)
}

@Singleton
class LeagueUserRepoImpl @Inject()()(implicit ec: LeagueExecutionContext) extends LeagueUserRepo{

  override def getLeagueUser(leagueId: Int, userId: Int): LeagueUser = {
    from(leagueUserTable)(lu => where(lu.leagueId === leagueId and lu.userId === userId)
      select lu)
        .single
  }

  override def getAllLeaguesForUser(userId: Int): Iterable[LeagueWithLeagueUser] = {
    from(leagueTable, userTable, leagueUserTable)((l, u, lu) => 
          where(u.id === userId and lu.userId === u.id and lu.leagueId === l.id)
          select(l, lu)
          ).map(q => LeagueWithLeagueUser(q._1, q._2))
  }
  override def getAllUsersForLeague(leagueId: Int): Iterable[UserWithLeagueUser] = {
    from(leagueTable, userTable, leagueUserTable)((l, u, lu) => 
          where(l.id === leagueId and lu.userId === u.id and lu.leagueId === l.id)
          select(u, lu)
          ).map(q => UserWithLeagueUser(q._1, q._2))
  }

  override def insertLeagueUser(league: League, userId: Int): LeagueUser = {
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

  override def insertLeagueUserStatDaily(leagueUserStatId: Long, day: Option[Int]): LeagueUserStatDaily = {
    leagueUserStatDailyTable.insert(new LeagueUserStatDaily(
      leagueUserStatId, day
    ))
  }

  override def getStatField(leagueId: Int, statFieldName: String): Option[LeagueStatField] = {
    Try(leagueStatFieldTable.where(
      lsf => lsf.leagueId === leagueId and lower(lsf.name) === statFieldName.toLowerCase()
    ).single).toOption
  }

  override def getRankings(league: League, statField: LeagueStatField, day: Option[Int]): LeagueRankings = {
    val rankings = this.getLeagueUserStatWithUser(league.id, statField.id, day)
    println(s"""rankings ${rankings.mkString(" ")}""")
    LeagueRankings(
      league.id, league.name, statField.name,
      rankings.zipWithIndex.map({case (q, i) => Ranking(q._1.id, q._1.username, q._3.value, i + 1, Some(q._2.previousRank))})
    )
  }
  override def getLeagueUserStat(
                                  leagueId: Int, statFieldId: Long, day: Option[Int]
                                ): Query[(LeagueUserStat, LeagueUserStatDaily)] = {
    from(
      leagueUserTable, leagueUserStatTable, leagueUserStatDailyTable
    )((lu, lus, s) =>
      where(
        lus.leagueUserId === lu.id and s.leagueUserStatId === lus.id and
          lu.leagueId === leagueId and lus.statFieldId === statFieldId and s.day === day
      )
        select (lus, s)
        orderBy (s.value desc)
    )
  }

  override def getLeagueUserStatWithUser(
                                  leagueId: Int, statFieldId: Long, day: Option[Int]
                                ): Query[(User, LeagueUserStat, LeagueUserStatDaily)] = {
      println(s"day: ${day}")
      from(
        userTable, leagueUserTable, leagueUserStatTable, leagueUserStatDailyTable
      )((u, lu, lus, s) =>
        where(
          lu.userId === u.id and lus.leagueUserId === lu.id and s.leagueUserStatId === lus.id and
            lu.leagueId === leagueId and lus.statFieldId === statFieldId and s.day === day
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

  override def getHistoricTeams(league: League, day: Int): Iterable[UserHistoricTeamOut] = {
    from(historicTeamPickeeTable, leagueUserTable, leagueTable, userTable, pickeeTable)(
      (h, lu, l, u, p) => where(lu.leagueId === league.id and h.day === day and u.id === lu.userId and h.pickeeId === p.id)
        select ((p, u))
        ).groupBy(_._2).map({case (user, v) => {
          UserHistoricTeamOut(user.id, user.externalId, user.username, v.map(_._1))
        }})
  }

  override def joinUsers(users: Iterable[User], league: League, statFields: Iterable[LeagueStatField], periods: Iterable[Period]) = {
    // TODO move to league user repo
    // // can ust pass stat field ids?
    val newLeagueUsers = users.map(u => insertLeagueUser(league, u.id))
    val newLeagueUserStats = statFields.flatMap(sf => newLeagueUsers.map(nlu => insertLeagueUserStat(sf.id, nlu.id)))

    newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.id, None))

    periods.foreach(p =>
      newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.id, Some(p.value)))
    )
  }
}

