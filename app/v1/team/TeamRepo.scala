package v1.team

import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models.AppDB._
import models._


class TeamExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TeamRepo{
  def getLeagueUserTeam(leagueUser: LeagueUser): List[Pickee]
}

@Singleton
class TeamRepoImpl @Inject()()(implicit ec: TeamExecutionContext) extends TeamRepo{
  override def getLeagueUserTeam(leagueUser: LeagueUser): List[Pickee] = {
    val query = join(teamPickeeTable, pickeeTable.leftOuter)((tp, p) => 
        where(tp.leagueUserId === leagueUser.id)
        select(p.get)
        on(tp.pickeeId === p.map(_.id))
        )
    query.toList
  }
}

