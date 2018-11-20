package v1.league

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models.AppDB._
import models.{League, LeagueStatField, Pickee, Period}
import utils.CostConverter

import scala.collection.mutable.ArrayBuffer

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait LeagueRepo{
  def get(id: Int): Option[League]
  def insert(formInput: LeagueFormInput): League
  def update(league: League, input: UpdateLeagueFormInput): League
  def getStatFieldNames(statFields: Iterable[LeagueStatField]): Array[String]
  def insertLeagueStatField(leagueId: Int, name: String): LeagueStatField
  def insertPeriod(leagueId: Int, input: PeriodInput, day: Int): Period
  def incrementDay(league: League)
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
      input.pickeeDescription,
      input.transferLimit, CostConverter.unconvertCost(input.startingMoney), input.teamSize
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

  override def insertPeriod(leagueId: Int, input: PeriodInput, day: Int): Period = {
    periodTable.insert(new Period(leagueId, day, input.start, input.end, input.multiplier))
  }

  override def incrementDay(league: League) = {
    // check if is above max?
    league.currentPeriod match {
        // TODO throw not print
      case Some(p) if !p.ended => println("Must end current day before start next")
      case Some(p) => {
        league.currentPeriod = Some(league.periods.find(np => np.value == p.value + 1).get)
        league.currentDay += 1
        leagueTable.update(league)
    }
      case None => {
        league.currentPeriod = Some(league.periods(0))
        league.currentDay += 1
        leagueTable.update(league)
      }
    }
  }
}

