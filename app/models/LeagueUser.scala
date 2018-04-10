package models

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.KeyedEntity
import java.sql.Timestamp

class LeagueUser(
                  val leagueId: Int,
                  val userId: Int,
                  var money: Double,
                  var entered: Timestamp,
                  var remainingTransfers: Int,
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