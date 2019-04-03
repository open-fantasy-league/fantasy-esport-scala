package models

import org.squeryl.KeyedEntity
import play.api.libs.json._

class Pickee(
              val leagueId: Long,
              var name: String,
              var externalId: Long, // in the case of dota we have the pickee id which is unique for Antimage in league 1
              // and Antimage in league 2. however we still want a field which is always AM hero id
              var cost: BigDecimal,
              var active: Boolean = true,
            ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val limits = AppDB.pickeeLimitTable.left(this)

}

object Pickee{
  implicit val implicitWrites = new Writes[Pickee] {
    def writes(p: Pickee): JsValue = {
      Json.obj(
        "id" -> p.externalId,
        "name" -> p.name,
        "cost" -> p.cost,
        "active" -> p.active,
      )
    }
  }
}

case class PickeeRow(pickeeId: Long, name: String, cost: BigDecimal)

object PickeeRow {
  implicit val implicitWrites = new Writes[PickeeRow] {
    def writes(x: PickeeRow): JsValue = {
      Json.obj(
        "id" -> x.pickeeId,
        "name" -> x.name,
        "cost" -> x.cost
      )
    }
  }
}


class TeamPickee(
                  var pickeeId: Long,
                  var teamId: Long
                ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val pickee = AppDB.pickeeToTeamPickee.right(this).single
}


class PickeeStat(
                       val statFieldId: Long,
                       val pickeeId: Long,
                       var previousRank: Int = 0
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class PickeeStatDaily(
                            val pickeeStatId: Long,
                            val period: Option[Int],
                            var value: Double = 0.0
                          ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class PickeeLimit(
                  val pickeeId: Long,
                  val limitId: Long,
                ) extends KeyedEntity[Long] {
  val id: Long = 0
}
