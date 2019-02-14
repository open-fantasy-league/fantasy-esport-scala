package models

import org.squeryl.KeyedEntity
import java.sql.Timestamp
import play.api.libs.json._

class LeagueUser(
                  val leagueId: Long,
                  val userId: Long,
                  var money: BigDecimal,
                  var entered: Timestamp,
                  var remainingTransfers: Option[Int],
                  var usedWildcard: Boolean,
                  var changeTstamp: Option[Timestamp] = None
                ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val team = AppDB.leagueUserToTeamPickee.left(this)
  lazy val league = AppDB.leagueUserTable.leftTable
  lazy val user = AppDB.leagueUserTable.rightTable
  lazy val transfers = AppDB.leagueUserToTransfer.left(this)
}

object LeagueUser{
  implicit val implicitWrites = new Writes[LeagueUser] {
    def writes(lu: LeagueUser): JsValue = {
      Json.obj(
        "userId" -> lu.userId,
        "leagueId" -> lu.leagueId,
        "money" -> lu.money,
        "entered" -> lu.entered,
        "remainingTransfers" -> lu.remainingTransfers,
        "usedWildcard" -> lu.usedWildcard,
        "transferScheduledTime" -> lu.changeTstamp
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
                       val period: Option[Int],
                       var value: Double = 0.0
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}
