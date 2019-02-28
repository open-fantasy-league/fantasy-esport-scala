package v1.team

import java.sql.Connection
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import v1.leagueuser.PickeeRow
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

import models.AppDB._
import models._

class TeamExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TeamRepo{
  def getLeagueUserTeam(leagueUser: LeagueUser)(implicit c: Connection): List[PickeeRow]
}

@Singleton
class TeamRepoImpl @Inject()()(implicit ec: TeamExecutionContext) extends TeamRepo{
  override def getLeagueUserTeam(leagueUser: LeagueUser)(implicit c: Connection): List[PickeeRow] = {
    val pickeeParser: RowParser[PickeeRow] = Macro.namedParser[PickeeRow](ColumnNaming.SnakeCase)
    val q =
      """select p.id, p.name, p.cost from team t join team_pickee tp on (tp.team_id = t.id) join pickee p on (tp.pickee_id = p.id)
    where t.league_user_id = {leagueUserId} and upper(t.timespan is NULL);
    """
    SQL(q).on("leagueUserId" -> leagueUser.id).as(pickeeParser.*).toList
  }
}

