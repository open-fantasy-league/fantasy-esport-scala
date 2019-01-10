package models

import org.squeryl.KeyedEntity
import play.api.libs.json._
import entry.SquerylEntrypointForMyApp._
import utils.CostConverter.convertCost

class Pickee(
              val leagueId: Long,
              var name: String,
              var externalId: Long, // in the case of dota we have the pickee id which is unique for Antimage in league 1
              // and Antimage in league 2. however we still want a field which is always AM hero id
              var cost: Int,
              var active: Boolean = true,
            ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val factions = AppDB.pickeeFactionTable.left(this)

}

object Pickee{
  implicit val implicitWrites = new Writes[Pickee] {
    def writes(p: Pickee): JsValue = {
      Json.obj(
        "id" -> p.externalId,
        "name" -> p.name,
        "cost" -> convertCost(p.cost),
        "active" -> p.active,
      )
    }
  }
}

class TeamPickee(
                  var pickeeId: Long,
                  var leagueUserId: Long
                ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val pickee = AppDB.pickeeToTeamPickee.right(this).single
}

/*object TeamPickee{
  implicit val implicitWrites = new Writes[TeamPickee] {
    def writes(p: TeamPickee): JsValue = {
      JsValue(p.pickee)
    }
  }
}*/

class HistoricTeamPickee(
                          var pickeeId: Long,
                          var leagueUserId: Long,
                          val period: Int
                        ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val pickee = AppDB.pickeeToHistoricTeamPickee.right(this).single
}

/*object HistoricTeamPickee{
  implicit val implicitWrites = new Writes[HistoricTeamPickee] {
    def writes(p: HistoricTeamPickee): JsValue = {
      JsValue(p.pickee)
    }
  }
}*/

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

class PickeeFaction(
                  val pickeeId: Long,
                  val factionId: Long,
                ) extends KeyedEntity[Long] {
  val id: Long = 0
}
