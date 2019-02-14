package v1.transfer

import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models._


class TransferExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TransferRepo{
  def getLeagueUserTransfer(leagueUser: LeagueUser, unprocessed: Option[Boolean]): List[Transfer]
}

@Singleton
class TransferRepoImpl @Inject()()(implicit ec: TransferExecutionContext) extends TransferRepo{
  override def getLeagueUserTransfer(leagueUser: LeagueUser, unprocessed: Option[Boolean]): List[Transfer] = {
  from(AppDB.transferTable)(t =>
    where(t.leagueUserId === leagueUser.id and (t.processed === unprocessed.map(!_).?))
    select t
    ).toList
  }
}

