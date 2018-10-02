package models

import java.sql.Timestamp

import org.squeryl.KeyedEntity
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
  //lazy val achievements = AppDB.userAchievementTable.right(this)
}

object User{
  implicit val implicitWrites = new Writes[User] {
    def writes(user: User): JsValue = {
      Json.obj(
        "id" -> user.id,
        "name" -> user.username,
        "email" -> user.email,
        "contacable" -> user.contactable
      )
    }
  }

}

class Friend(
              val userId: Int,
              val friendId: Int
            ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Transfer(
                // TODO add in timestamp?
                val leagueUserId: Long,
                val pickeeId: Long,
                val isBuy: Boolean,
                val scheduledFor: Timestamp,
                val cost: Double,
                val processed: Boolean = false
) extends KeyedEntity[Long] {
  val id: Long = 0

//  lazy val tradePickee = (pickeeId: Long, tradeInput: tradeInputForm){
//    if (true){
//      tradeInputForm.isBuy match {
//        case true => "aaa"
//        case false => "bbb"
//      }
//    }
//  }
}

//class UserXp(
//              val userId: Int,
//              var xp: Long,
//              // had these in python/sql alchemy. they can technically just be queried from league standings
//              // in the name of doing things properly will leave them out for now unless queries are slow
//              //highest_daily_pos = Column(Integer)
//              //highest_weekly_pos = Column(Integer)
//              //all_time_points = Column(BigInteger, default=0)
//            ) extends KeyedEntity[Int] {
//  val id: Int = 0
//}

//class Achievement(
//                   val gameId: Int,
//                   var name: String,
//                   var description: String,
//                   var xp: Long
//                 ) extends KeyedEntity[Int] {
//  val id: Int = 0
//  lazy val users = AppDB.userAchievementTable.left(this)
//}
//
//
//class UserAchievement(
//                       val achievementId: Int,
//                       var userId: Int,
//                       var description: String,
//                       var xp: Long
//                     ) extends KeyedEntity[Int] {
//  val id: Int = 0
//}

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