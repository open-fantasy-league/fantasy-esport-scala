package models

import org.squeryl.KeyedEntity
import java.sql.Timestamp

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
}


class LeagueUserStat(
                       val statFieldId: Long,
                       val leagueUserId: Long,
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class LeagueUserStatDaily(
                       val leagueUserStatId: Long,
                       val day: Int,
                       var value: Double = 0.0
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class LeagueUserStatOverall(
                            val leagueUserStatId: Long,
                            var value: Double = 0.0,
                            var oldRank: Int = 0,
                          ) extends KeyedEntity[Long] {
  val id: Long = 0
}