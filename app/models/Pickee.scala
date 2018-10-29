package models

import org.squeryl.KeyedEntity

class Pickee(
              val leagueId: Int,
              var name: String,
              var externalId: Int, // in the case of dota we have the pickee id which is unique for Antimage in league 1
              // and Antimage in league 2. however we still want a field which is always AM hero id
              var faction: Option[String],
              var cost: Int,
              var active: Boolean = true
            ) extends KeyedEntity[Long] {
  val id: Long = 0

  def this() = this(0, "", 0, None, 0, true)
}

class TeamPickee(
                  var pickeeId: Long,
                  var leagueUserId: Long
                  // different field for scoring and scheduledForSale because with delays, a hero can be scheduled to be sold
                  // but still be currently earning points.
                // soldTstamp
                // boughtTstamp
                ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class HistoricTeamPickee(
                          var pickeeId: Long,
                          var leagueUserId: Long,
                          val day: Int
                        ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class PickeeStat(
                       val statFieldId: Long,
                       val pickeeId: Long,
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class PickeeStatDaily(
                            val pickeeStatId: Long,
                            val day: Option[Int],
                            var value: Double = 0.0
                          ) extends KeyedEntity[Long] {
  val id: Long = 0
}