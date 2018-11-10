package models

import org.squeryl.KeyedEntity
import java.sql.Timestamp
import play.api.libs.json._

class LeagueUser(
                  val leagueId: Int,
                  val userId: Int,
                  var money: Int,
                  var entered: Timestamp,
                  var remainingTransfers: Option[Int],
                  var changeTstamp: Option[Timestamp] = None
                ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val team = AppDB.leagueUserToTeamPickee.left(this)
  lazy val league = AppDB.leagueUserTable.leftTable
}

object LeagueUser{
  implicit val implicitWrites = new Writes[LeagueUser] {
    def writes(lu: LeagueUser): JsValue = {
      Json.obj(
        "id" -> lu.id,
        "userId" -> lu.userId,
        "leagueId" -> lu.leagueId,
      )
    }
  }
}


class LeagueUserStat(
                       val statFieldId: Long,
                       val leagueUserId: Long,
                       var previousRank: Int = 0
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val leagueUser = AppDB.leagueUserToLeagueUserStat.right(this)
}

class LeagueUserStatDaily(
                       val leagueUserStatId: Long,
                       val day: Option[Int],
                       var value: Double = 0.0
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}