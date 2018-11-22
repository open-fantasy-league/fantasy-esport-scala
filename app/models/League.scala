package models

import java.sql.Timestamp
import org.joda.time.DateTime

import org.squeryl.KeyedEntity
import play.api.libs.json._

import scala.collection.mutable.ArrayBuffer
import entry.SquerylEntrypointForMyApp._

class League(
              var name: String,
              val apiId: Int, // the api user/platform that created the league
              val gameId: Int,
              var isPrivate: Boolean,
              var tournamentId: Int,
              var pickeeDescription: String,
              var transferLimit: Option[Int],
              var transferWildcard: Boolean,
              var startingMoney: Int,
              val teamSize: Int = 5,
              var transferDelay: Int = 0, // Only applies for when day 1 has started
              var refundPeriod: Int = 0,
              var transferOpen: Boolean = false,
              var pointsMultiplier: Double = 1.0,
              var unfilledTeamPenaltyMultiplier: Double = 0.5,
              var url: String = "",
              var autoUpdate: Boolean = true,
              var currentPeriodId: Option[Long] = None
            ) extends KeyedEntity[Int] {
  val id: Int = 0

  lazy val users = AppDB.leagueUserTable.left(this)
  lazy val pickees = AppDB.leagueToPickee.left(this)
  lazy val statFields = from(AppDB.leagueToLeagueStatField.left(this))(select(_)).toList
  lazy val factionTypes = from(AppDB.leagueToFactionType.left(this))(select(_)).toList
  lazy val periods = from(AppDB.leagueToPeriod.left(this))(select(_)).toList
  //lazy val currentPeriod = from(AppDB.leagueToPeriod.left(this))((p) => where(p.id === this.currentPeriodId)).single
  def currentPeriod: Option[Period] = AppDB.periodTable.lookup(this.currentPeriodId.getOrElse(-1L))
  //lazy val currentPeriod: Option[Period] = this.periods.find(p => p.id == this.currentPeriodId)
  //def currentPeriod: Option[Period] = from(AppDB.leagueToPeriod.left(this))(select(_)).toList.find(_.id == this.currentPeriodId)
  lazy val started = this.currentPeriodId.isEmpty
  lazy val ended = this.periods.takeRight(0)(0).ended
  //lazy val prize: ManyToOne[LeaguePrize] = AppDB.leagueToLeaguePrize.right(this)

  // If a class has an Option[] field, it becomes mandatory to implement a zero argument constructor
  def this() = this("", 1, 1, false, 0, "", None, false, 0)

}

class LeagueStatField(
                       val leagueId: Int,
                       val name: String  // Index this
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class LeaguePrize(
                   val leagueId: Int,
                   var description: String,
                   var email: String
                 ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class FactionType(
                  val leagueId: Int,
                  val name: String,
                  var description: String,
                  var max: Option[Int] = None
                 ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val factions = from(AppDB.factionTypeToFaction.left(this))(select(_)).toList
}

class Faction(
                   val factionTypeId: Long,
                   val name: String,
                   var max: Int,
                 ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Period(
            val leagueId: Int,
            val value: Int,
            var start: Timestamp,
            var end: Timestamp,
            var multiplier: Double = 1.0,
            var ended: Boolean = false,
            ) extends KeyedEntity[Long] {
  val id: Long = 0

  def this() = this(
    0, 0, new Timestamp(System.currentTimeMillis()), new Timestamp(System.currentTimeMillis())
  )
}


object Faction{
  implicit val implicitWrites = new Writes[Faction] {
    def writes(f: Faction): JsValue = {
      Json.obj(
        "name" -> f.name,
        "max" -> f.max
      )
    }
  }
}

object LeaguePrize{
  implicit val implicitWrites = new Writes[LeaguePrize] {
    def writes(lp: LeaguePrize): JsValue = {
      Json.obj(
        "description" -> lp.description,
        "email" -> lp.email
      )
    }
  }
}

object Period{
  implicit val implicitWrites = new Writes[Period] {
    def writes(p: Period): JsValue = {
      Json.obj(
        "value" -> p.value,
        "start" -> p.start,
        "end" -> p.end,
        "multiplier" -> p.multiplier
      )
    }
  }
}

object FactionType{
  implicit val implicitWrites = new Writes[FactionType] {
    def writes(ft: FactionType): JsValue = {
      Json.obj(
        "name" -> ft.name,
        "description" -> ft.description,
        "factions" -> ft.factions
      )
    }
  }
}

object League{
  implicit val implicitWrites = new Writes[League] {
    def writes(league: League): JsValue = {
      Json.obj(
        "id" -> league.id,
        "name" -> league.name,
        "gameId" -> league.gameId,
        "tournamentId" -> league.tournamentId,
        "isPrivate" -> league.isPrivate,
        "tournamentId" -> league.tournamentId,
        "pickee" -> league.pickeeDescription,
        "pointsMultiplier" -> league.pointsMultiplier,
        "teamSize" -> league.teamSize,
        "transferLimit" -> league.transferLimit, // use -1 for no transfer limit I think. only applies after day 1 start
        "startingMoney" -> league.startingMoney,
        "statFields" -> league.statFields.map(_.name),
        "factionTypes" -> league.factionTypes,
        "periods" -> league.periods,
        "currentPeriod" -> league.currentPeriod
      )
    }
  }
}
