package v1.leagueuser

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import org.squeryl.Query
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import scala.util.Try

import models._
import utils.CostConverter

import scala.collection.mutable.ArrayBuffer

case class Ranking(userId: Int, username: String, value: Double, rank: Int, oldRank: Option[Int])

case class LeagueRankings(leagueId: Int, leagueName: String, statField: String, rankings: Iterable[Ranking])

object Ranking{
  implicit val implicitWrites = new Writes[Ranking] {
    def writes(ranking: Ranking): JsValue = {
      Json.obj(
        "userId" -> ranking.userId,
        "username" -> ranking.username,
        "value" -> ranking.value,
        "rank" -> ranking.rank,
        "oldRank" -> ranking.oldRank
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
  def insertLeagueUserStatDaily(leagueUserStatId: Long, day: Int): LeagueUserStatDaily
  def insertLeagueUserStatOverall(leagueUserStatId: Long): LeagueUserStatOverall
  def getStatField(leagueId: Int, statFieldName: String): Option[LeagueStatField]
  def getRankings(league: League, statField: LeagueStatField, day: Option[Int]): LeagueRankings

  //private def statFieldIdFromName(statFieldName: String, leagueId: Int)
}

@Singleton
class LeagueUserRepoImpl @Inject()()(implicit ec: LeagueExecutionContext) extends LeagueUserRepo{

  override def insertLeagueUser(league: League, userId: Int): LeagueUser = {
    AppDB.leagueUserTable.insert(new LeagueUser(
      league.id, userId, league.startingMoney, new Timestamp(System.currentTimeMillis()), league.transferLimit
    ))
  }

  override def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long): LeagueUserStat = {
    AppDB.leagueUserStatTable.insert(new LeagueUserStat(
      statFieldId, leagueUserId
    ))
  }

  override def insertLeagueUserStatDaily(leagueUserStatId: Long, day: Int): LeagueUserStatDaily = {
    AppDB.leagueUserStatDailyTable.insert(new LeagueUserStatDaily(
      leagueUserStatId, day
    ))
  }

  override def insertLeagueUserStatOverall(leagueUserStatId: Long): LeagueUserStatOverall = {
    AppDB.leagueUserStatOverallTable.insert(new LeagueUserStatOverall(
      leagueUserStatId
    ))
  }

  override def getStatField(leagueId: Int, statFieldName: String): Option[LeagueStatField] = {
    Try(AppDB.leagueStatFieldTable.where(
      lsf => lsf.leagueId === leagueId and lower(lsf.name) === statFieldName.toLowerCase()
    ).single).toOption
  }

  override def getRankings(league: League, statField: LeagueStatField, day: Option[Int]): LeagueRankings = {
    val statTable = if (day.nonEmpty) AppDB.leagueUserStatDailyTable else AppDB.leagueUserStatOverallTable
    val rankings = from (
      AppDB.userTable, AppDB.leagueUserTable, AppDB.leagueUserStatTable, AppDB.leagueUserStatOverallTable
    )((u, lu, lus, s) =>
      where(
        lu.userId === u.id and lus.leagueUserId === lu.id and s.leagueUserStatId === lus.id and
          lu.leagueId === league.id and lus.statFieldId === statField.id
      )
        // TODO add s.oldrank
      select((u.id, u.username, s.value))
      orderBy(s.value desc)
    )
    LeagueRankings(
      league.id, league.name, statField.name,
      rankings.zipWithIndex.map({case (q, i) => Ranking(q._1, q._2, q._3, i + 1, None)})
    )
  }
}

