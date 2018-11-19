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
              var startingMoney: Int,
              val teamSize: Int = 5,
              var transferDelay: Int = 0, // Only applies for when day 1 has started
              var refundPeriod: Int = 0,
              var transferOpen: Boolean = false,
              var currentDay: Int = 0, // TODO currentDay to currentPeriod
              var pointsMultiplier: Double = 1.0,
              var unfilledTeamPenaltyMultiplier: Double = 0.5,
              var phase: Int = 0,
              var url: String = "",
              var autoUpdate: Boolean = true,
              var started: Boolean = false,
              var ended: Boolean = false
            ) extends KeyedEntity[Int] {
  val id: Int = 0

  lazy val users = AppDB.leagueUserTable.left(this)
  lazy val pickees = AppDB.leagueToPickee.left(this)
  lazy val statFields = from(AppDB.leagueToLeagueStatField.left(this))(select(_)).toList
  lazy val factions = from(AppDB.leagueToFactionType.left(this))(select(_)).toList
  //lazy val prize: ManyToOne[LeaguePrize] = AppDB.leagueToLeaguePrize.right(this)

  // If a class has an Option[] field, it becomes mandatory to implement a zero argument constructor
  def this() = this(
    "", 1, 1, false, 0, "", None, 0, 5, 0,
    0, false, 0, 1.0, 0.5, 0, "", true, false, false
  )

}

class LeagueStatField(
                       val leagueId: Int,
                       val name: String  // Index this
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
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
        "currentDay" -> league.currentDay,
        "pointsMultiplier" -> league.pointsMultiplier,
        "teamSize" -> league.teamSize,
        "transferLimit" -> league.transferLimit, // use -1 for no transfer limit I think. only applies after day 1 start
        "startingMoney" -> league.startingMoney,
        "statFields" -> league.statFields.map(_.name)
      )
    }
  }
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
            ) extends KeyedEntity[Long] {
  val id: Long = 0
}