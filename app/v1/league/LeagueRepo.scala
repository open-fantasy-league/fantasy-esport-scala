package v1.league

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models.AppDB._
import models.{League, LeagueStatField, Pickee}
import utils.CostConverter

import scala.collection.mutable.ArrayBuffer

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait LeagueRepo{
  def get(id: Int): Option[League]
  def insert(formInput: LeagueFormInput): League
  def update(league: League, input: UpdateLeagueFormInput): League
  def getStatFieldNames(statFields: Iterable[LeagueStatField]): Array[String]
  def insertLeagueStatField(leagueId: Int, name: String): LeagueStatField
  def incrementDay(league: League)
  def getPickees(leagueId: Int): Iterable[Pickee]
}

@Singleton
class LeagueRepoImpl @Inject()()(implicit ec: LeagueExecutionContext) extends LeagueRepo{
  override def get(id: Int): Option[League] = {
    leagueTable.lookup(id)
  }

  override def getStatFieldNames(statFields: Iterable[LeagueStatField]): Array[String] = {
    statFields.map(_.name).toArray
  }

  override def insert(input: LeagueFormInput): League = {
    leagueTable.insert(new League(input.name, 1, input.gameId, input.isPrivate, input.tournamentId,
      input.totalDays, new Timestamp(input.dayStart), new Timestamp(input.dayEnd), input.pickeeDescription,
      input.transferLimit, input.factionLimit, input.factionDescription,
      CostConverter.unconvertCost(input.startingMoney), input.teamSize
    ))
  }

  override def update(league: League, input: UpdateLeagueFormInput): League = {
    league.name = input.name.getOrElse(league.name)
    league.isPrivate = input.isPrivate.getOrElse(league.isPrivate)
    // etc for other fields
    leagueTable.update(league)
    league
  }

  override def insertLeagueStatField(leagueId: Int, name: String): LeagueStatField = {
    leagueStatFieldTable.insert(new LeagueStatField(leagueId, name))
  }

  override def incrementDay(league: League) = {
    // check if is above max?
    league.currentDay += 1
    leagueTable.update(league)
  }

  override def getPickees(leagueId: Int): Iterable[Pickee] = {
    from(pickeeTable, leagueTable)(
      (p, l) => where(p.leagueId === l.id)
      select(p)
    )
  }
}

