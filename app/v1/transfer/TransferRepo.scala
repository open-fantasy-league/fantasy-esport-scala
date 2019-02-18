package v1.transfer

import java.sql.Timestamp

import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import models.AppDB.{leagueUserTable, teamPickeeTable, teamTable, transferTable}
import play.api.libs.concurrent.CustomExecutionContext
import models._

import scala.collection.immutable.List


class TransferExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TransferRepo{
  def getLeagueUserTransfer(leagueUser: LeagueUser, unprocessed: Option[Boolean]): List[Transfer]
  def processLeagueUserTransfer(leagueUser: LeagueUser): Unit
  def changeTeam(leagueUser: LeagueUser, toBuyIds: Set[Long], toSellIds: Set[Long],
                 oldTeamIds: Set[Long], oldTeam: Option[Team], time: Timestamp
                )
}

@Singleton
class TransferRepoImpl @Inject()()(implicit ec: TransferExecutionContext) extends TransferRepo{
  override def getLeagueUserTransfer(leagueUser: LeagueUser, unprocessed: Option[Boolean]): List[Transfer] = {
  from(AppDB.transferTable)(t =>
    where(t.leagueUserId === leagueUser.id and (t.processed === unprocessed.map(!_).?))
    select t
    ).toList
  }

  override def changeTeam(
                           leagueUser: LeagueUser, toBuyIds: Set[Long], toSellIds: Set[Long],
                           oldTeamIds: Set[Long], oldTeam: Option[Team], time: Timestamp
                         ) = {
    val newTeam = teamTable.insert(new Team(leagueUser.id, time))
    val newPickees: Set[Long] = oldTeamIds -- toBuyIds ++ toSellIds
    oldTeam.foreach(t => {t.ended = Some(time); teamTable.update(t)})
    leagueUser.changeTstamp = None
    leagueUserTable.update(leagueUser)
    newPickees.map(t => teamPickeeTable.insert(new TeamPickee(t, newTeam.id)))
  }

  override def processLeagueUserTransfer(leagueUser: LeagueUser) = {
    val now = new Timestamp(System.currentTimeMillis())
    // TODO need to lock here?
    // TODO map and filter together
    println("in proc trans")
    val transfers = transferTable.where(t => t.processed === false and t.leagueUserId === leagueUser.id)
    // TODO single iteration
    val toSellIds = transfers.filter(!_.isBuy).map(_.pickeeId).toSet
    val toBuyIds = transfers.filter(_.isBuy).map(_.pickeeId).toSet
    val oldTeamQ: Iterable[(Team, Option[Long])] = join(teamTable, teamPickeeTable.leftOuter)((t, tp) =>
      where(t.leagueUserId === leagueUser.id and t.ended.isNull)
        select (t, tp.map(_.pickeeId))
        on(tp.map(_.teamId) === t.id)
    )
    val oldTeamIds = oldTeamQ.flatMap(_._2).toSet
    val oldTeam = oldTeamQ.headOption.map(_._1)
    changeTeam(leagueUser, toBuyIds, toSellIds, oldTeamIds, oldTeam, now)
    transferTable.update(transfers.map(t => {
      t.processed = true; t
    }))
  }
}

