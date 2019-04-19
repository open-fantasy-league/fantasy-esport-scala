package models

import java.time.LocalDateTime
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import play.api.libs.json._

case class StatDailyRow(
                                   statFieldId: Long, statFieldName: String, previousRank: Int, value: Double,
                                   period: Option[Int]
                                 )

case class PickeeRow(internalPickeeId: Long, externalPickeeId: Long, name: String, cost: BigDecimal)

case class PickeeLimitsRow(internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, cost: BigDecimal, limitType: String, limitName: String, max: Int)

case class PickeeLimitsAndStatsRow(
                                    internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, cost: BigDecimal, limitType: String,
                                    limitName: String, max: Int, statFieldName: String, value: Double, previousRank: Int)

object PickeeRow {
  implicit val implicitWrites = new Writes[PickeeRow] {
    def writes(x: PickeeRow): JsValue = {
      Json.obj(
        "id" -> x.externalPickeeId,
        "name" -> x.name,
        "cost" -> x.cost
      )
    }
  }

  val parser: RowParser[PickeeRow] = Macro.namedParser[PickeeRow](ColumnNaming.SnakeCase)
}

case class TeamRow(externalUserId: Long, username: String, leagueUserId: Long, start: Option[LocalDateTime],
                   end: Option[LocalDateTime], isActive: Boolean, internalPickeeId: Long, externalPickeeId: Long, pickeeName: String,
                   pickeeCost: BigDecimal)

object TeamRow {
//  implicit val implicitWrites = new Writes[TeamRow] {
//    def writes(x: TeamRow): JsValue = {
//      Json.obj(
//        "userId" -> x.externalUserId,
//        "username" -> x.username,
//        "leagueUserId" -> x.leagueUserId,
//        "start" -> x.start,
//        "end" -> x.end,
//        "isActive" -> x.isActive,
//        "pickeeId" -> x.externalPickeeId,
//        "name" -> x.name,
//        "cost" -> x.cost
//      )
//    }
//  }

  val parser: RowParser[TeamRow] = Macro.namedParser[TeamRow](ColumnNaming.SnakeCase)
}

case class PickeeStatsOut(pickee: PickeeRow, limits: Map[String, String], stats: Map[String, Double])

object PickeeStatsOut{
  implicit val implicitWrites = new Writes[PickeeStatsOut] {
    def writes(p: PickeeStatsOut): JsValue = {
      Json.obj(
        "id" -> p.pickee.externalPickeeId,
        "name" -> p.pickee.name,
        "stats" -> p.stats,
        "limits" -> p.limits,
        "cost" -> p.pickee.cost,
        "active" -> true //p.pickee.active  TODO reimplement active
      )
    }
  }
}