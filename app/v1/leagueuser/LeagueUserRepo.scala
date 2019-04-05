package v1.leagueuser

import java.sql.{Connection, Timestamp}
import java.time.LocalDateTime
//import java.math.BigDecimal

import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import org.squeryl.{KeyedEntity, Query, Table}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import play.api.db._
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import anorm.SqlParser.long

import scala.util.Try
import models.AppDB._
import models._
import utils.GroupByOrderedImplicit._

import v1.team.TeamRepo
import v1.transfer.TransferRepo
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo

case class Ranking(userId: Long, username: String, value: Double, rank: Int, previousRank: Option[Int], team: Option[Iterable[PickeeRow]])

case class LeagueRankings(leagueId: Long, leagueName: String, statField: String, rankings: Iterable[Ranking])

case class UserHistoricTeamOut(id: Long, externalId: Long, username: String, team: Iterable[Pickee])

case class UserWithLeagueUser(user: User, info: LeagueUser)

case class LeagueWithLeagueUser(league: League, info: LeagueUser)

case class RankingRow(
                       userId: Long, username: String, value: Double, previousRank: Option[Int], pickeeId: Option[Long],
                       pickeeName: Option[String], cost: Option[BigDecimal]
                     )
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
  def insertLeagueUser(league: LeagueRow, userId: Long): LeagueUser
  def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long): LeagueUserStat
  def insertLeagueUserStatDaily(leagueUserStatId: Long, period: Option[Int]): LeagueUserStatDaily
  def getStatField(leagueId: Long, statFieldName: String): Option[LeagueStatField]
  def getRankings(
                   league: LeagueRow, statField: LeagueStatField, period: Option[Int], includeTeam: Boolean, userIds: Option[Array[Long]],
                   secondaryOrdering: Option[List[Long]]
                 )(implicit c: Connection): LeagueRankings
  def getLeagueUserStats(leagueId: Long, statFieldId: Long, period: Option[Int]): Query[(LeagueUserStat, LeagueUserStatDaily)]
  def getLeagueUserStatsWithUser(leagueId: Long, statFieldId: Long, period: Option[Int]): Query[(User, LeagueUserStat, LeagueUserStatDaily)]
  def leagueUserStatsAndTeamQuery(leagueId: Long, statFieldId: Long, period: Option[Int],
                                   timestamp: Option[LocalDateTime], secondaryOrdering: Option[List[Long]])(implicit c: Connection):
    Iterable[RankingRow]
  def getLeagueUserStatsAndTeam(league: LeagueRow, statFieldId: Long, period: Option[Int], timestamp: Option[LocalDateTime], secondaryOrdering: Option[List[Long]])(implicit c: Connection):
  Iterable[RankingRow]
  def getSingleLeagueUserAllStat(leagueUser: LeagueUser, period: Option[Int]): Iterable[(LeagueStatField, LeagueUserStatDaily)]
  def updateLeagueUserStatDaily(newLeagueUserStatsDaily: Iterable[LeagueUserStatDaily])
  def updateLeagueUserStat(newLeagueUserStats: Iterable[LeagueUserStat])
  //def getHistoricTeams(league: LeagueRow, period: Int): Iterable[LeagueUserTeamOut]
//  def joinUsers(users: Iterable[User], league: League): Iterable[LeagueUser]
  def joinUsers2(users: Iterable[User], league: LeagueRow)(implicit c: Connection): Iterable[LeagueUser]
  def userInLeague(userId: Long, leagueId: Long): Boolean
  def getShouldProcessTransfer(leagueId: Long)(implicit c: Connection): Iterable[Long]
  def updateHistoricRanks(league: League)

  //private def statFieldIdFromName(statFieldName: String, leagueId: Long)
}

@Singleton
class LeagueUserRepoImpl @Inject()(db: Database, transferRepo: TransferRepo, teamRepo: TeamRepo, leagueRepo: LeagueRepo, pickeeRepo: PickeeRepo)(implicit ec: LeagueExecutionContext) extends LeagueUserRepo{

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
          Some(teamRepo.getLeagueUserTeam(leagueUser.id))
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

  override def insertLeagueUser(league: LeagueRow, userId: Long): LeagueUser = {
    // dont give wildcard to people who join league late
    leagueUserTable.insert(new LeagueUser(
      league.id, userId, league.startingMoney, LocalDateTime.now(), league.transferLimit,
      !league.transferWildcard || (leagueRepo.isStarted(league) && league.noWildcardForLateRegister)
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
                            league: LeagueRow, statField: LeagueStatField, period: Option[Int], includeTeam: Boolean,
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
          Ranking(q.userId, q.username, value, rank, q.previousRank, Some(team))
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
                                                   timestamp: Option[LocalDateTime], secondaryOrdering: Option[List[Long]])(implicit c: Connection): Iterable[RankingRow] = {
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
                                          league: LeagueRow, statFieldId: Long, period: Option[Int],
                                          timestamp: Option[LocalDateTime], secondaryOrdering: Option[List[Long]]
                                        )(implicit c: Connection):
    Iterable[RankingRow] = {
    // hahaha. rofllwefikl!s
    (period, leagueRepo.getCurrentPeriod(league), timestamp) match {
      case (None, _, None) => this.leagueUserStatsAndTeamQuery(league.id, statFieldId, None, None, secondaryOrdering)
      case (_, None, _) => this.leagueUserStatsAndTeamQuery(league.id, statFieldId, None, None, secondaryOrdering)
      case (Some(periodVal), Some(currentPeriod), None) if periodVal == currentPeriod.value =>
        this.leagueUserStatsAndTeamQuery(league.id, statFieldId, Some(periodVal), None,secondaryOrdering)
      case (Some(_), _, Some(_)) => throw new Exception("Specify period, or timestamp. not both")
      case (None, _, Some(t)) => {
        val period = leagueRepo.getPeriodBetween(league.id, t).get
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

  //override def getHistoricTeams(league: LeagueRow, period: Int): Iterable[LeagueUserTeamOut] = {
//    val endPeriodTstamp = from(periodTable)(
//      p => where(p.value === period and p.leagueId === league.id)
//        select p
//    ).single.end
//    join(userTable, leagueUserTable, teamTable.leftOuter, teamPickeeTable.leftOuter, pickeeTable.leftOuter)(
//      (u, lu, t, tp, p) => where(lu.leagueId === league.id and t.map(_.started).map(_ < endPeriodTstamp)
//        and t.map(_.ended).map(_ >= endPeriodTstamp))
//        select ((lu, p))
//      on(lu.userId === u.id, t.map(_.leagueUserId) === lu.id,
//        tp.map(_.teamId) === t.map(_.id), tp.map(_.pickeeId) === p.map(_.id))
//        ).groupBy(_._1).map({case (lu, v) => {
//          LeagueUserTeamOut(lu, v.flatMap(_._2))
//        }})
//  }

//  override def joinUsers(users: Iterable[User], league: League): Iterable[LeagueUser] = {
//    // TODO move to league user repo
//    // // can ust pass stat field ids?
//    val newLeagueUsers = users.map(u => insertLeagueUser(league, u.id))
//    val newLeagueUserStats = league.getStatFields.flatMap(sf => newLeagueUsers.map(nlu => insertLeagueUserStat(sf.id, nlu.id)))
//
//    newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.id, None))
//
//    leagueRepo.getPeriods(league).foreach(p =>
//      newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.id, Some(p.value)))
//    )
//
//    newLeagueUsers
//  }

  override def joinUsers2(users: Iterable[User], league: LeagueRow)(implicit c: Connection): Iterable[LeagueUser] = {
    // TODO move to league user repo
    // // can ust pass stat field ids?
    val newLeagueUsers = users.map(u => insertLeagueUser(league, u.id))
    val newLeagueUserStats = leagueRepo.getStatFields(league).flatMap(sf => newLeagueUsers.map(nlu => insertLeagueUserStat(sf.id, nlu.id)))

    newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.id, None))

    leagueRepo.getPeriods(league).foreach(p =>
      newLeagueUserStats.foreach(nlu => insertLeagueUserStatDaily(nlu.id, Some(p.value)))
    )

    newLeagueUsers
  }

  override def userInLeague(userId: Long, leagueId: Long): Boolean = {
    from(leagueUserTable)(lu => where(lu.leagueId === leagueId and lu.userId === userId).select(1)).nonEmpty
  }

  override def getShouldProcessTransfer(leagueId: Long)(implicit c: Connection): Iterable[Long] = {
    val q = "select league_user_id from league_user where league_id = {leagueId} and change_tstamp <= now();"
    SQL(q).on("leagueId" -> leagueId).as(long("league_user_id").*)
  }

  override def updateHistoricRanks(league: League) = {
    // TODO this needs to group by the stat field.
    // currently will do weird ranks
    league.statFields.foreach(sf => {
      val leagueUserStatsOverall = getLeagueUserStats(league.id, sf.id, None)
      var lastScore = Double.MaxValue // TODO java max num
      var lastScoreRank = 0
      val newLeagueUserStat = leagueUserStatsOverall.zipWithIndex.map({
        case ((lus, s), i) => {
          val value = s.value
          val rank = if (value == lastScore) lastScoreRank else i + 1
          lastScore = value
          lastScoreRank = rank
          lus.previousRank = rank
          lus
        }
      })
      // can do all update in one call if append then update outside loop
      updateLeagueUserStat(newLeagueUserStat)
      val pickeeStatsOverall = pickeeRepo.getPickeeStat(league.id, sf.id, None).map(_._1)
      val newPickeeStat = pickeeStatsOverall.zipWithIndex.map(
        { case (p, i) => p.previousRank = i + 1; p }
      )
      // can do all update in one call if append then update outside loop
      pickeeStatTable.update(newPickeeStat)
    })
  }
}

