package models

import java.sql.Timestamp
import org.joda.time.DateTime

import org.squeryl.KeyedEntity
import play.api.libs.json._

import scala.collection.mutable.ArrayBuffer
import entry.SquerylEntrypointForMyApp._

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
              val apiUserId: Int, // the api user/platform that created the league
              val gameId: Int,
              var isPrivate: Boolean,
              var tournamentId: Int,
              var totalDays: Int,
              var dayStart: Timestamp,
              var dayEnd: Timestamp,
              var pickeeDescription: String,
              //val captain: Boolean,
              var transferLimit: Option[Int],
              var factionLimit: Option[Int],
              var factionDescription: Option[String],
              var startingMoney: Int,
              val teamSize: Int = 5,
              var transferDelay: Int = 0, // Only applies for when day 1 has started
              var refundPeriod: Int = 0,
              var transferOpen: Boolean = false,
              var currentDay: Int = 0,
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
  //lazy val prize: ManyToOne[LeaguePrize] = AppDB.leagueToLeaguePrize.right(this)

  //def dayIter: Iter[Int] = Seq(0, this.totalDays) // append -1

  // If a class has an Option[] field, it becomes mandatory
  // to implement a zero argument constructor
  def this() = this(
    "", 1, 1, false, 0, 0, new Timestamp(System.currentTimeMillis()), new Timestamp(System.currentTimeMillis()), "", None, None, None, 0, 5, 0,
    0, false, 0, 1.0, 0.5, 0, "", true, false, false
  )

}

class LeagueStatField(
                       val leagueId: Int,
                       val name: String  // Index this
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}

object LeagueStatField{
  implicit val implicitWrites = new Writes[LeagueStatField] {
    def writes(lsf: LeagueStatField): JsValue = {
      Json.obj(
        "name" -> lsf.name
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
        "totalDays" -> league.totalDays,
        "pickee" -> league.pickeeDescription,
        "dayStart" -> league.dayStart,
        "dayEnd" -> league.dayEnd,
        "currentDay" -> league.currentDay,
        "pointsMultiplier" -> league.pointsMultiplier,
        "teamSize" -> league.teamSize,
        "transferLimit" -> league.transferLimit, // use -1 for no transfer limit I think. only applies after day 1 start
        "startingMoney" -> league.startingMoney,
        "statFields" -> league.statFields
      )
    }
  }
}

// var (\w+): \w+[^,\n]+
// "$1" -> league.$1

class LeaguePrize(
                   val leagueId: Int,
                   var description: String,
                   var email: String
                 ) extends KeyedEntity[Int] {
  val id: Int = 0
}

case class LeaguePlusStuff(league: League, lsf: Array[String])


object LeaguePlusStuff{
  implicit val implicitWrites = new Writes[LeaguePlusStuff] {
    def writes(leagueps: LeaguePlusStuff): JsValue = {
      Json.obj(
        "id" -> leagueps.league.id,
        "name" -> leagueps.league.name,
        "gameId" -> leagueps.league.gameId,
        "tournamentId" -> leagueps.league.tournamentId,
        "isPrivate" -> leagueps.league.isPrivate,
        "tournamentId" -> leagueps.league.tournamentId,
        "totalDays" -> leagueps.league.totalDays,
        "pickee" -> leagueps.league.pickeeDescription,
        "dayStart" -> leagueps.league.dayStart,
        "dayEnd" -> leagueps.league.dayEnd,
        "pointsMultiplier" -> leagueps.league.pointsMultiplier,
        "teamSize" -> leagueps.league.teamSize,
        //val captain: Boolean,
        "transferLimit" -> leagueps.league.transferLimit, // use -1 for no transfer limit I think. only applies after day 1 start
        "startingMoney" -> leagueps.league.startingMoney,
        "statFields" -> leagueps.lsf
      )
    }
  }
}