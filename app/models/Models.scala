package models

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.{Schema, KeyedEntity}
import java.sql.Timestamp
import play.api.libs.json._

class User(
            var username: String,
            var password: String,
            var email: Option[String],
            var contactable: Boolean
          ) extends KeyedEntity[Int] {
  val id: Int = 0

  // If a class has an Option[] field, it becomes mandatory
  // to implement a zero argument constructor
  def this() = this("", "", Some(""), false)

  lazy val leagues = AppDB.leagueUserTable.right(this)
  lazy val achievements = AppDB.userAchievementTable.right(this)
}

class APIUser(
               var name: String,
               var apikey: String,
               var email: String,
               var role: Int // TODO this should be enum
             ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class Game(
            val name: String,
            val code: String,
            var pickee: String, //i.e. Hero, champion, player
          ) extends KeyedEntity[Int] {
  val id: Int = 0
}

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

class LeagueUser(
                  val leagueId: Int,
                  val userId: Int,
                  var money: Double,
                  var lateStart: Boolean,
                  var remainingTransfers: Int,
                  var lateStartTstamp: Option[Timestamp],
                  var changeTstamp: Option[Timestamp]
                ) extends KeyedEntity[Long] {
  val id: Long = 0
}


class LeagueUserStats(
                       val statFieldId: Long,
                       val leagueUserId: Long,
                       val day: Int,
                       var value: Double,
                       var oldRank: Int = 0,
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Pickee(
              var name: String,
              var identifier: Int, // in the case of dota we have the pickee id which is unique for AM in league 1
              // and AM in league 2. however we still want a field which is always AM hero id
              val leagueId: Int,
              var faction: Option[String],
              var value: Double,
              var active: Boolean = true
            ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class TeamPickee(
                  var pickeeId: Long,
                  var leagueUserId: Long,
                  // have a day = -1 for live team
                  // then we can copy/duplicate team-pickee for storing historic team throughout league
                  val day: Int,
                  // different field for active and reserve because with delays, a hero can be scheduled to be moved into
                  // reserves, but still be currently earning points.
                  var active: Boolean = true,
                  var reserve: Boolean = false,
                ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class PickeeStats(
                   val statFieldId: Long,
                   val pickeeId: Long,
                   val day: Int,
                   var value: Double,
                   var oldRank: Int = 0,
                 ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Friend(
              val userId: Int,
              val friendId: Int
            ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Sale(
            val leagueUserId: Long,
            val pickeeId: Long,
            val isBuy: Boolean,
            val isReserve: Boolean,
            val timestamp: Timestamp,
            val cost: Double
          ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Result(
              val matchId: Long,
              val pickeeId: Long,
              val startTstamp: Timestamp,
              val addedTstamp: Timestamp,
              var isTeamOne: Boolean, // for showing results
              val appliedPickee: Boolean = false,
              val appliedUser: Boolean = false,
              // maybe want a field that stores points for results.
              // rather than having to sum points matches every time want to show match results.
            ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class PointsField(
                   var name: String,
                   val leagueId: Int,
                   // let the field be either a boolean 0/1 such as pick. or an int/double such as camps stacked or gpm
                   var multiplier: Double,
                 ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Points(
              val resultId: Long,
              val pointsFieldId: Long,
              var value: Double
            ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Matchu( // because match is an sql keyword
              val leagueId: Int,
              val identifier: Int, // this is the dota2 match id field
              // we dont want to have 2 different games where they can overlap primary key. so dont use match id as primary key
              val day: Int,
              var tournamentId: Int, // for displaying link to tournament page. tournament can differ from league
              var teamOne: String,
              var teamTwo: String,
              var teamOneVictory: Boolean
            )
  extends KeyedEntity[Long] {
  val id: Long = 0
}

class HallOfFame(
                  val gameId: Int,
                  val leagueId: Int,
                  var winnerUserId: Int,
                  var runnerUpUserId: Int

                ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class UserXp(
              val userId: Int,
              var xp: Long,
              // had these in python/sql alchemy. they can technically just be queried from league standings
              // in the name of doing things properly will leave them out for now unless queries are slow
              //highest_daily_pos = Column(Integer)
              //highest_weekly_pos = Column(Integer)
              //all_time_points = Column(BigInteger, default=0)
            ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class Achievement(
                   val gameId: Int,
                   var name: String,
                   var description: String,
                   var xp: Long
                 ) extends KeyedEntity[Int] {
  val id: Int = 0
  lazy val users = AppDB.userAchievementTable.left(this)
}


class UserAchievement(
                       val achievementId: Int,
                       var userId: Int,
                       var description: String,
                       var xp: Long
                     ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class Notification(
                    var userId: Int,
                    var message: String,
                    var link: Option[String],
                    var seen: Boolean = false
                  ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class PasswordReset(
                     // TODO are guid/time stuff vars or actually vals?
                     val userId: Long,
                     var guid: String,
                     //var time: Timestamp,
                     var ip: String, // This is so can ip block anyone who spam resets passwords for someone
                     var counter: Int = 1, // Don't let people get spammed
                   ) extends KeyedEntity[Long] {
  val id: Long = 0
}


object AppDB extends Schema {
  val userTable = table[User]
  val leagueTable = table[League]
  val gameTable = table[Game]
  val passwordResetTable = table[PasswordReset]
  val apiUserTable = table[APIUser]
  val leagueUserStatsTable = table[LeagueUserStats]
  val leagueStatFieldsTable = table[LeagueStatFields]
  val pickeeTable = table[Pickee]
  val teamPickeeTable = table[TeamPickee]
  val pickeeStatsTable = table[PickeeStats]
  val friendTable = table[Friend]
  val saleTable = table[Sale]
  val resultTable = table[Result]
  val pointsFieldTable = table[PointsField]
  val pointsTable = table[Points]
  val matchTable = table[Matchu]
  val hallOfFameTable = table[HallOfFame]
  val userXpTable = table[UserXp]
  val achievementTable = table[Achievement]
  val notificationTable = table[Notification]

  val leagueTransferSettingsTable = table[LeagueTransferSettings]
  val leaguePrizeTable = table[LeaguePrize]

  // League User has many to many relation. each user can play in many leagues. each league can have many users
  // TODO this should be true of user achievements as well
  val leagueUserTable =
  manyToManyRelation(leagueTable, userTable).
    via[LeagueUser]((l, u, lu) => (lu.leagueId === l.id, u.id === lu.userId))

  val userAchievementTable =
    manyToManyRelation(achievementTable, userTable).
      via[UserAchievement]((a, u, ua) => (ua.achievementId === a.id, u.id === ua.userId))

  // lets do all our oneToMany foreign keys
  val leagueUserToLeagueUserStats =
    oneToManyRelation(leagueUserTable, leagueUserStatsTable).
      via((lu, lus) => (lu.id === lus.leagueUserId))

  val leagueToLeaguePrize =
    oneToManyRelation(leagueTable, leaguePrizeTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToLeagueTransferInfo =
    oneToManyRelation(leagueTable, leagueTransferSettingsTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToLeagueStatFields =
    oneToManyRelation(leagueTable, leagueStatFieldsTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToPickee =
    oneToManyRelation(leagueTable, pickeeTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToPointsField =
    oneToManyRelation(leagueTable, pointsFieldTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToMatch =
    oneToManyRelation(leagueTable, matchTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToHallOfFame =
    oneToManyRelation(leagueTable, hallOfFameTable).
      via((l, o) => (l.id === o.leagueId))

  val pickeeToTeamPickee =
    oneToManyRelation(pickeeTable, teamPickeeTable).
      via((p, o) => (p.id === o.pickeeId))

  val pickeeToPickeeStats =
    oneToManyRelation(pickeeTable, pickeeStatsTable).
      via((p, o) => (p.id === o.pickeeId))

  val pickeeToResult =
    oneToManyRelation(pickeeTable, resultTable).
      via((p, o) => (p.id === o.pickeeId))

  val pickeeToSale =
    oneToManyRelation(pickeeTable, saleTable).
      via((p, o) => (p.id === o.pickeeId))

  val userToNotification =
    oneToManyRelation(userTable, notificationTable).
      via((u, o) => (u.id === o.userId))

  val userToUserXp =
    oneToManyRelation(userTable, userXpTable).
      via((u, o) => (u.id === o.userId))

  val userToPasswordReset =
    oneToManyRelation(userTable, passwordResetTable).
      via((u, o) => (u.id === o.userId))

  val userToFriend =
    oneToManyRelation(userTable, friendTable).
      via((u, o) => (u.id === o.userId))

  val userToFriendTwo =
    oneToManyRelation(userTable, friendTable).
      via((u, o) => (u.id === o.friendId))

  val gameToHallOfFame =
    oneToManyRelation(gameTable, hallOfFameTable).
      via((g, o) => (g.id === o.gameId))

  val gameToLeague =
    oneToManyRelation(gameTable, leagueTable).
      via((g, o) => (g.id === o.gameId))

  val gameToAchievement =
    oneToManyRelation(gameTable, achievementTable).
      via((g, o) => (g.id === o.gameId))

}