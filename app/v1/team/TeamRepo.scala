package v1.team

import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models.AppDB._
import models._


class TeamExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TeamRepo{
  def getLeagueUserTeam(leagueUser: LeagueUser): List[(Team, Option[Pickee])]
}

@Singleton
class TeamRepoImpl @Inject()()(implicit ec: TeamExecutionContext) extends TeamRepo{
  override def getLeagueUserTeam(leagueUser: LeagueUser): List[(Team, Option[Pickee])] = {
    val query = join(teamTable, teamPickeeTable.leftOuter, pickeeTable.leftOuter)((t, tp, p) =>
        where(t.leagueUserId === leagueUser.id)
        select(t, p)
        on(tp.map(_.teamId) === t.id, tp.map(_.pickeeId) === p.map(_.id))
        )
    query.toList
  }
}

