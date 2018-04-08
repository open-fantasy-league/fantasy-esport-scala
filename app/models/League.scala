package models

import java.sql.Timestamp

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.KeyedEntity
import play.api.libs.json._

class League(
              var name: String,
              val gameId: Int,
              var isPrivate: Boolean,
              var tournamentId: Int,
              var totalDays: Int,
              var dayStart: Timestamp,
              var dayEnd: Timestamp,
              var currentDay: Int = 0,
              var pointsMultiplier: Double = 1.0,
              var unfilledTeamPenaltyMultiplier: Double = 0.5,
              var phase: Int = 0,
              var url: String = ""
            ) extends KeyedEntity[Int] {
  val id: Int = 0

  lazy val users = AppDB.leagueUserTable.left(this)
  lazy val prize: ManyToOne[LeaguePrize] = AppDB.leagueToLeaguePrize.right(this)
  lazy val transferSettings: ManyToOne[LeagueTransferSettings] = AppDB.leagueToLeagueTransferSettings.right(this)

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
        "totalDays" -> league.totalDays,
        "dayStart" -> league.dayStart,
        "dayEnd" -> league.dayEnd,
        "currentDay" -> league.currentDay,
        "pointsMultiplier" -> league.pointsMultiplier,
        "unfilledTeamPenaltyMultiplier" -> league.unfilledTeamPenaltyMultiplier,
        "phase" -> league.phase,
        "url" -> league.url
      )
    }
  }
}

// var (\w+): \w+[^,\n]+
// "$1" -> league.$1

class LeaguePrize(
                   val leagueId: Int,
                   var prizeDescription: String,
                   var prizeEmail: String,
                 ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class LeagueTransferSettings(
                              val leagueId: Int,
                              val teamSize: Int,
                              val reserveSize: Int,
                              val captain: Boolean,
                              var transferLimit: Int, // use -1 for no transfer limit I think
                              var startingMoney: Double,
                              var changeDelay: Int = 0, // change is generic for swap or transfer
                              var factionLimit: Int = 0,
                              // Dont allow systems where can clash swapping and transferring.
                              var transferOpen: Boolean = false,
                              var swapOpen: Boolean = false,

                            ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class LeagueStatFields(
                        val name: String,
                        val leagueId: Int
                      ) extends KeyedEntity[Long] {
  val id: Long = 0
}