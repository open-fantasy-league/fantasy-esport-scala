package models

import java.sql.Timestamp

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.KeyedEntity
import org.squeryl.customtypes.CustomTypesMode._
import org.squeryl.customtypes._
import play.api.libs.json._

//trait Domain[Int] {
//  self: CustomType[Int] =>
//  def label: String
//  def validate(a: Int): Unit
//  def value: Int
//  validate(value)
//}
//
//class TeamSize(v: Int) extends IntField(v) with Domain[Int] {
//  def validate(teamSize: Int) = assert(1 <= teamSize && teamSize <= 20, "team size must be between 1 and 20, got " + teamSize)
//  def label = "teamSize"
//}

class League(
              var name: String,
              val gameId: Int,
              var isPrivate: Boolean,
              var tournamentId: Int,
              var totalDays: Int,
              var dayStart: Timestamp,
              var dayEnd: Timestamp,
              val teamSize: Int = 5,
              //val captain: Boolean,
              var transferLimit: Int, // use -1 for no transfer limit I think. only applies after day 1 start
              var startingMoney: Double,
              var transferDelay: Int = 0, // Only applies for when day 1 has started
              var refundPeriod: Int = 0,
              var transferOpen: Boolean = false,
              var currentDay: Int = 0,
              var pointsMultiplier: Double = 1.0,
              var unfilledTeamPenaltyMultiplier: Double = 0.5,
              var phase: Int = 0,
              var url: String = "",
              var autoUpdate: Boolean = true,
            ) extends KeyedEntity[Int] {
  val id: Int = 0

  lazy val users = AppDB.leagueUserTable.left(this)
  //lazy val prize: ManyToOne[LeaguePrize] = AppDB.leagueToLeaguePrize.right(this)

  //def dayIter: Iter[Int] = Seq(0, this.totalDays) // append -1

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
        "pointsMultiplier" -> league.pointsMultiplier,
        "teamSize" -> league.teamSize,
        //val captain: Boolean,
        "transferLimit" -> league.transferLimit, // use -1 for no transfer limit I think. only applies after day 1 start
        "startingMoney" -> league.startingMoney
      )
    }
  }
}

// var (\w+): \w+[^,\n]+
// "$1" -> league.$1

class LeaguePrize(
                   val leagueId: Int,
                   var description: String,
                   var email: String,
                 ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class LeagueFaction(
                     val leagueId: Int,
                     var name: String,
                     var limit: Int = 0  // 0 is essentially no limit
                   ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class LeagueStatFields(
                        val name: String,  // Index this
                        val leagueId: Int
                      ) extends KeyedEntity[Long] {
  val id: Long = 0
}