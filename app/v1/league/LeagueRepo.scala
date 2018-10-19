package v1.league

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models._
import utils.CostConverter

import scala.collection.mutable.ArrayBuffer

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait LeagueRepo{
  def show(id: Int): Option[League]
  def insertLeague(formInput: LeagueFormInput): League
  def getStatFields(league: League): Array[String]
  def insertLeagueStatField(leagueId: Int, name: String): LeagueStatFields
}

@Singleton
class LeagueRepoImpl @Inject()()(implicit ec: LeagueExecutionContext) extends LeagueRepo{
  override def show(id: Int): Option[League] = {
    AppDB.leagueTable.lookup(id)
  }

  override def getStatFields(league: League): Array[String] = {
    league.statFields.map(_.name).toArray
//    val statFields = ArrayBuffer[String]()
//    for (f <- league.statFields) {
//      println(f.name)
//      statFields += f.name
//    }
//    statFields
  }

  override def insertLeague(input: LeagueFormInput): League = {
    AppDB.leagueTable.insert(new League(input.name, 1, input.gameId, input.isPrivate, input.tournamentId,
      input.totalDays, new Timestamp(input.dayStart), new Timestamp(input.dayEnd), input.pickeeDescription,
      input.transferLimit, input.factionLimit, input.factionDescription,
      CostConverter.unconvertCost(input.startingMoney), input.teamSize
    ))
  }

  override def insertLeagueStatField(leagueId: Int, name: String): LeagueStatFields = {
    AppDB.leagueStatFieldsTable.insert(new LeagueStatFields(leagueId, name))
  }
}

