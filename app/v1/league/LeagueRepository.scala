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
  def add(formInput: LeagueFormInput): League
  def getStatFields(league: League): Array[String]
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

  override def add(input: LeagueFormInput): League = {
    val newLeague = AppDB.leagueTable.insert(new League(input.name, 1, input.gameId, input.isPrivate, input.tournamentId,
      input.totalDays, new Timestamp(input.dayStart), new Timestamp(input.dayEnd), input.pickeeDescription,
      input.transferLimit, input.factionLimit, input.factionDescription,
      CostConverter.unconvertCost(input.startingMoney), input.teamSize
    ))

    val statFields: ArrayBuffer[Long] = ArrayBuffer()
    val pointsField = AppDB.leagueStatFieldsTable.insert(new LeagueStatFields(
      newLeague.id, "points"
    ))

    statFields += pointsField.id

    // TODO make sure stat fields static cant be changed once tournament in progress
    input.extraStats match {
      case Some(extraStats) => {
        for (extraStat <- extraStats) {
          val newStatField = AppDB.leagueStatFieldsTable.insert (new LeagueStatFields (
            newLeague.id, extraStat
          ))
          statFields += newStatField.id
        }
      }
      case None =>
    }

    for (pickee <- input.pickees) {
      val newPickee = AppDB.pickeeTable.insert(new Pickee(
        newLeague.id,
        pickee.name,
        pickee.id, // in the case of dota we have the pickee id which is unique for AM in league 1
        // and AM in league 2. however we still want a field which is always AM hero id
        pickee.faction,
        CostConverter.unconvertCost(pickee.value),
        pickee.active
      ))

      // -1 is for whole tournament
      for (day <- -1 until input.totalDays) {
        for (statFieldId <- statFields) {
          AppDB.pickeeStatsTable.insert(new PickeeStats(
            statFieldId, newPickee.id, day
          ))
        }
      }
    }
    newLeague
  }
}

