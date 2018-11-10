package models

import org.squeryl.KeyedEntity
import play.api.libs.json._

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

object Pickee{
  implicit val implicitWrites = new Writes[Pickee] {
    def writes(p: Pickee): JsValue = {
      Json.obj(
        "internalId" -> p.id,
        "externalId" -> p.externalId,
        "name" -> p.name,
        "faction" -> p.faction,
        "cost" -> p.cost,
        "active" -> p.active
      )
    }
  }
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
  lazy val pickee = AppDB.pickeeToTeamPickee.right(this).single
}

object TeamPickee{
  implicit val implicitWrites = new Writes[TeamPickee] {
    def writes(p: TeamPickee): JsValue = {
      Json.obj(
        "id" -> p.id,
        "pickee" -> p.pickee
      )
    }
  }
}

class HistoricTeamPickee(
                          var pickeeId: Long,
                          var leagueUserId: Long,
                          val day: Int
                        ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val pickee = AppDB.pickeeToHistoricTeamPickee.right(this).single
}

object HistoricTeamPickee{
  implicit val implicitWrites = new Writes[HistoricTeamPickee] {
    def writes(p: HistoricTeamPickee): JsValue = {
      Json.obj(
        "day" -> p.day,
        "pickee" -> p.pickee
      )
    }
  }
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