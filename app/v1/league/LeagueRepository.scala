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

trait LeagueRepository{
  def show(id: Int): Option[League]
  def insertLeague(formInput: LeagueFormInput): League
  def getStatFields(league: League): Array[String]
  def insertLeagueStatField(leagueId: Int, name: String): LeagueStatFields
  def insertPickee(leagueId: Int, pickee: PickeeFormInput): Pickee
  def insertPickeeStats(statFieldId: Long, pickeeId: Long, day: Int): PickeeStats
  //def update()
}

@Singleton
class LeagueRepositoryImpl @Inject()()(implicit ec: LeagueExecutionContext) extends LeagueRepository{
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
  override def insertPickee(leagueId: Int, pickee: PickeeFormInput): Pickee = {
    AppDB.pickeeTable.insert(new Pickee(
      leagueId,
      pickee.name,
      pickee.id, // in the case of dota we have the pickee id which is unique for AM in league 1
      // and AM in league 2. however we still want a field which is always AM hero id
      pickee.faction,
      CostConverter.unconvertCost(pickee.value),
      pickee.active
    ))

  }
  override def insertPickeeStats(statFieldId: Long, pickeeId: Long, day: Int): PickeeStats = {
    AppDB.pickeeStatsTable.insert(new PickeeStats(
      statFieldId, pickeeId, day
    ))
  }
}

