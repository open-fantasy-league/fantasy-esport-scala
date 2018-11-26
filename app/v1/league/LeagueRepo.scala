package v1.league

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._

import models.AppDB._
import models._
import utils.CostConverter

import scala.collection.mutable.ArrayBuffer

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class LeagueFull(league: League, factions: Iterable[FactionTypeOut], periods: Iterable[Period], currentPeriod: Option[Period], statFields: Iterable[LeagueStatField])

object LeagueFull{
  implicit val implicitWrites = new Writes[LeagueFull] {
    def writes(league: LeagueFull): JsValue = {
      Json.obj(
        "id" -> league.league.id,
        "name" -> league.league.name,
        "gameId" -> league.league.gameId,
        "tournamentId" -> league.league.tournamentId,
        "isPrivate" -> league.league.isPrivate,
        "tournamentId" -> league.league.tournamentId,
        "pickee" -> league.league.pickeeDescription,
        "pointsMultiplier" -> league.league.pointsMultiplier,
        "teamSize" -> league.league.teamSize,
        "transferLimit" -> league.league.transferLimit, // use -1 for no transfer limit I think. only applies after day 1 start
        "startingMoney" -> league.league.startingMoney,
        "statFields" -> league.statFields.map(_.name),
        "factionTypes" -> league.factions,
        "periods" -> league.periods,
        "currentPeriod" -> league.currentPeriod
      )
    }
  }
}

case class LeagueFullQuery(league: League, period: Option[Period], factionType: Option[FactionType], faction: Option[Faction], statField: Option[LeagueStatField])


trait LeagueRepo{
  def get(id: Int): Option[League]
  def getWithRelated(id: Int): Option[LeagueFull]
  def insert(formInput: LeagueFormInput): League
  def update(league: League, input: UpdateLeagueFormInput): League
  def getStatFieldNames(statFields: Iterable[LeagueStatField]): Array[String]
  def insertLeagueStatField(leagueId: Int, name: String): LeagueStatField
  def insertPeriod(leagueId: Int, input: PeriodInput, day: Int): Period
  def incrementDay(league: League)
  def leagueFullQueryExtractor(q: Iterable[LeagueFullQuery]): Option[LeagueFull]
}

@Singleton
class LeagueRepoImpl @Inject()()(implicit ec: LeagueExecutionContext) extends LeagueRepo{
  override def get(id: Int): Option[League] = {
    leagueTable.lookup(id)
  }

  override def getWithRelated(id: Int): Option[LeagueFull] = {
    val queryResult = join(leagueTable, periodTable.leftOuter, factionTypeTable.leftOuter, factionTable.leftOuter, leagueStatFieldTable.leftOuter)((l, p, ft, f, s) => 
        where(l.id === id)
        select((l, p, ft, f, s))
        on(l.id === p.map(_.leagueId), l.id === ft.map(_.leagueId), f.map(_.factionTypeId) === ft.map(_.id), s.map(_.leagueId) === l.id)
        ).map(q => LeagueFullQuery(q._1,q._2, q._3,q._4, q._5))
    leagueFullQueryExtractor(queryResult)
        // deconstruct tuple
        // check what db queries would actuallly return
  }

  override def getStatFieldNames(statFields: Iterable[LeagueStatField]): Array[String] = {
    statFields.map(_.name).toArray
  }

  override def insert(input: LeagueFormInput): League = {
    leagueTable.insert(new League(input.name, 1, input.gameId, input.isPrivate, input.tournamentId,
      input.pickeeDescription, input.transferLimit, input.transferWildcard,
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

  override def insertPeriod(leagueId: Int, input: PeriodInput, day: Int): Period = {
    periodTable.insert(new Period(leagueId, day, input.start, input.end, input.multiplier))
  }

  override def incrementDay(league: League) = {
    // check if is above max?
    league.currentPeriod match {
      case Some(p) if !p.ended => println("Must end current day before start next")
      case Some(p) => {
        league.currentPeriodId = Some(league.periods.find(np => np.value == p.value + 1).get.id)
        leagueTable.update(league)
    }
      case None => {
        league.currentPeriodId = Some(league.periods(0).id)
        leagueTable.update(league)
      }
    }
  }

  override def leagueFullQueryExtractor(q: Iterable[LeagueFullQuery]): Option[LeagueFull] = {
    if (q.isEmpty) return None
    val league = q.toList.head.league
    val periods = q.flatMap(_.period).toSet
    val currentPeriod = periods.find(_.id == league.currentPeriodId)
    val statFields = q.flatMap(_.statField).toSet
    val factions = q.map(f => (f.factionType, f.faction)).filter(!_._2.isEmpty).map(f => (f._1.get, f._2.get)).groupBy(_._1).mapValues(_.map(_._2).toSet)
    // keep factions as well
    val factionsOut = factions.map({case (k, v) => FactionTypeOut(k.name, k.description, v)})
    Some(LeagueFull(league, factionsOut, periods, currentPeriod, statFields))
  }
}

