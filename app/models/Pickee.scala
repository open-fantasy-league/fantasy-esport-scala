package models

import org.squeryl.KeyedEntity

class Pickee(
              val leagueId: Int,
              var name: String,
              var identifier: Int, // in the case of dota we have the pickee id which is unique for AM in league 1
              // and AM in league 2. however we still want a field which is always AM hero id
              var faction: Option[String],
              var cost: Int,
              var active: Boolean = true
            ) extends KeyedEntity[Long] {
  val id: Long = 0

  def this() = this(0, "", 0, None, 0, true)
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
                  var reserve: Boolean = false
                ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class PickeeStats(
                   val statFieldId: Long,
                   val pickeeId: Long,
                   val day: Int,
                   var value: Double = 0.0,
                   var oldRank: Int = 0
                 ) extends KeyedEntity[Long] {
  val id: Long = 0
}