package models

import play.api.libs.json.{JsValue, Json, Writes}
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class StatsRow(value: Double)

object StatsRow{
//  implicit val implicitWrites = new Writes[StatsRow] {
//    def writes(p: StatsRow): JsValue = {
//      Json.obj(
//        // TODO fix the fuck
//        "cat" -> p.value
//      )
//    }
//  }

  val parser: RowParser[StatsRow] = Macro.namedParser[StatsRow](ColumnNaming.SnakeCase)
}
