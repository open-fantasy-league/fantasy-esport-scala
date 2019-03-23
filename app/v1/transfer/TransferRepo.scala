package v1.transfer

import java.sql.{Connection, Timestamp}

import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import models.AppDB.{leagueUserTable, teamPickeeTable, teamTable, transferTable}
import play.api.libs.concurrent.CustomExecutionContext
import anorm._
import play.api.db._
import models._

import scala.collection.immutable.List


class TransferExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TransferRepo{
  def getLeagueUserTransfer(leagueUser: LeagueUser, unprocessed: Option[Boolean]): List[Transfer]
  def processLeagueUserTransfer(leagueUser: LeagueUser)(implicit c: Connection): Unit
  def changeTeam(leagueUser: LeagueUser, toBuyIds: Set[Long], toSellIds: Set[Long],
                 oldTeamIds: Set[Long], time: Timestamp
                )(implicit c: Connection)
}

@Singleton
class TransferRepoImpl @Inject()()(implicit ec: TransferExecutionContext) extends TransferRepo{
  override def getLeagueUserTransfer(leagueUser: LeagueUser, unprocessed: Option[Boolean]): List[Transfer] = {
  from(AppDB.transferTable)(t =>
    where(t.leagueUserId === leagueUser.id and (t.processed === unprocessed.map(!_).?))
    select t
    ).toList
  }
  // ALTER TABLE team ALTER COLUMN id SET DEFAULT nextval('team_seq');
  override def changeTeam(leagueUser: LeagueUser, toBuyIds: Set[Long], toSellIds: Set[Long],
                           oldTeamIds: Set[Long], time: Timestamp
                         )(implicit c: Connection) = {
      val newPickees: Set[Long] = (oldTeamIds -- toSellIds) ++ toBuyIds
      val q =
        """update team t set timespan = tstzrange(lower(timespan), now())
    where t.league_user_id = {leagueUserId} and upper(t.timespan) is NULL;
    """
      SQL(q).on("leagueUserId" -> leagueUser.id).executeUpdate()
    println("Ended current team")
    val newTeamId = SQL(
      "insert into team(league_user_id, timespan) values ({leagueUserId}, tstzrange({now}, null));"
    ).on("leagueUserId" -> leagueUser.id, "now" -> time).executeInsert()
    println("Inserted new team")
    leagueUser.changeTstamp = None
    leagueUserTable.update(leagueUser)
    print(newPickees.mkString(", "))
    newPickees.map(t => teamPickeeTable.insert(new TeamPickee(t, newTeamId.get)))
  }

  override def processLeagueUserTransfer(leagueUser: LeagueUser)(implicit c: Connection)  = {
    val now = new Timestamp(System.currentTimeMillis())
    // TODO need to lock here?
    // TODO map and filter together
    val transfers = transferTable.where(t => t.processed === false and t.leagueUserId === leagueUser.id)
    // TODO single iteration
    val toSellIds = transfers.filter(!_.isBuy).map(_.pickeeId).toSet
    val toBuyIds = transfers.filter(_.isBuy).map(_.pickeeId).toSet
      val q =
        """select pickee_id from team t join team_pickee tp on (tp.team_id = t.id)
                  where t.league_user_id = {leagueUserId} and upper(t.timespan) is NULL;
              """
      val oldTeamIds = SQL(q).on("leagueUserId" -> leagueUser.id).as(SqlParser.scalar[Long] *).toSet
      changeTeam(leagueUser, toBuyIds, toSellIds, oldTeamIds, now)
    transferTable.update(transfers.map(t => {
      t.processed = true; t
    }))
  }
}

