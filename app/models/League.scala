package models

import java.sql.Timestamp

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.KeyedEntity

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

}

object League{
  implicit val implicitWrites = new Writes[League] {
    def writes(league: League): JsValue = {
      Json.obj(
        "name" -> league.name,
        "gameId" -> league.gameId,
        "tournamentId" -> league.tournamentId
      )
    }
  }
}