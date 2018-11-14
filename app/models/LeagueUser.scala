package models

import org.squeryl.KeyedEntity
import java.sql.Timestamp
import play.api.libs.json._
import utils.CostConverter._

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
  lazy val user = AppDB.leagueUserTable.rightTable
  lazy val transfers = AppDB.leagueUserToTransfer.left(this)
}

case class FullLeagueUser(
                           leagueUser: LeagueUser, user: User, team: Iterable[TeamPickee], transfers: Iterable[Transfer]
                         )

//TODO is there a .all?
object FullLeagueUser{
  implicit val implicitWrites = new Writes[FullLeagueUser] {
    def writes(w: FullLeagueUser): JsValue = {
      Json.obj(
        "id" -> w.leagueUser.id,
        "userId" -> w.user.id,
        "leagueId" -> w.leagueUser.leagueId,
        "username" -> w.user.username,
        "money" -> unconvertCost(w.leagueUser.money),
        "entered" -> w.leagueUser.entered,
        "remainingTransfers" -> w.leagueUser.remainingTransfers,
        "team" -> w.team,
        "scheduledTransferTime" -> w.leagueUser.changeTstamp,
        "scheduledTransfers" -> w.transfers
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