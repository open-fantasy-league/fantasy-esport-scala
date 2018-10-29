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
import models.{League, User, LeagueUser, LeagueStatField, LeagueUserStat, LeagueUserStatDaily}
import utils.CostConverter

import scala.collection.mutable.ArrayBuffer

case class Ranking(userId: Int, username: String, value: Double, rank: Int, previousRank: Option[Int])

case class LeagueRankings(leagueId: Int, leagueName: String, statField: String, rankings: Iterable[Ranking])

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
  def insertLeagueUser(league: League, userId: Int): LeagueUser
  def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long): LeagueUserStat
  def insertLeagueUserStatDaily(leagueUserStatId: Long, day: Option[Int]): LeagueUserStatDaily
  def getStatField(leagueId: Int, statFieldName: String): Option[LeagueStatField]
  def getRankings(league: League, statField: LeagueStatField, day: Option[Int]): LeagueRankings
  def getLeagueUserStat[T](leagueId: Int, statFieldId: Long, day: Option[Int], withUser: Boolean): Query[T]
//  def getLeagueUserStatOverallWithUser(leagueId: Int, statFieldId: Long): Query[(User, LeagueUserStatOverall)]
//  def getLeagueUserStatDaily(leagueId: Int, statFieldId: Long): Query[LeagueUserStatOverall]
//  def getLeagueUserStatDailyWithUser(leagueId: Int, statFieldId: Long): Query[(User, LeagueUserStatOverall)]
//  def updateLeagueUserStat(newLeagueUserStatsOverall: Iterable[LeagueUserStatOverall]): Unit
  def updateLeagueUserStatDaily(newLeagueUserStatsDaily: Iterable[LeagueUserStatDaily])
  def updateLeagueUserStat(newLeagueUserStats: Iterable[LeagueUserStat])

  //private def statFieldIdFromName(statFieldName: String, leagueId: Int)
}

@Singleton
class LeagueUserRepoImpl @Inject()()(implicit ec: LeagueExecutionContext) extends LeagueUserRepo{

  override def insertLeagueUser(league: League, userId: Int): LeagueUser = {
    leagueUserTable.insert(new LeagueUser(
      league.id, userId, league.startingMoney, new Timestamp(System.currentTimeMillis()), league.transferLimit
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
    val rankings = this.getLeagueUserStat[(User, LeagueUserStat, LeagueUserStatDaily)](league.id, statField.id, day, true)
    LeagueRankings(
      league.id, league.name, statField.name,
      rankings.zipWithIndex.map({case (q, i) => Ranking(q._1.id, q._1.username, q._3.value, i + 1, Some(q._2.previousRank))})
    )
  }
  override def getLeagueUserStat[T](leagueId: Int, statFieldId: Long, day: Option[Int], withUser: Boolean): Query[T] = {
    // TODO conditionally return user and stats, or just stats
    withUser match {
      case false => {
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
      case true => {
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
    }
  }
//  //TODO dry above and below
//  override def getLeagueUserStatOverallWithUser(leagueId: Int, statFieldId: Long): Query[(User, LeagueUserStatOverall)] = {
//    // TODO conditionally return user and stats, or just stats
//    //TODO just npass in table?
//    //val statTable = leagueUserStatOverallTable
//    from (
//      userTable, leagueUserTable, leagueUserStatTable, table
//    )((u, lu, lus, s) =>
//      where(
//        lu.userId === u.id and lus.leagueUserId === lu.id and s.leagueUserStatId === lus.id and
//          lu.leagueId === leagueId and lus.statFieldId === statFieldId
//      )
//        // TODO add s.oldrank
//        select((u, s))
//        orderBy(s.value desc)
//    )
//  }
//
  override def updateLeagueUserStatDaily(newLeagueUserStatsDaily: Iterable[LeagueUserStatDaily]): Unit = {
    leagueUserStatDailyTable.update(newLeagueUserStatsDaily)
  }

  override def updateLeagueUserStat(newLeagueUserStats: Iterable[LeagueUserStat]): Unit = {
    leagueUserStatTable.update(newLeagueUserStats)
  }
}

