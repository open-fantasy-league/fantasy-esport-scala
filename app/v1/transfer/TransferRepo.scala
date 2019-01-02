package v1.transfer

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import org.squeryl.{Query, Table, KeyedEntity}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._

import models.AppDB._
import models._


class TransferExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TransferRepo{
  def getLeagueUserTransfer(leagueUser: LeagueUser, onlyPending: Option[Boolean]): List[Transfer]
}

@Singleton
class TransferRepoImpl @Inject()()(implicit ec: TransferExecutionContext) extends TransferRepo{
  override def getLeagueUserTransfer(leagueUser: LeagueUser, onlyPending: Option[Boolean]): List[Transfer] = {
  from(AppDB.transferTable)(t =>
    where(t.leagueUserId === leagueUser.id and not (t.processed === onlyPending.?))
    select(t)
    ).toList
  }
}

