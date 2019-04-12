package models

import play.api.libs.json.{JsValue, Json, Writes}
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class PointsRow(value: Double)

object PointsRow{
  implicit val implicitWrites = new Writes[PointsRow] {
    def writes(p: PointsRow): JsValue = {
      Json.obj(
        // TODO fix the fuck
        "cat" -> p.value
      )
    }
  }

  val parser: RowParser[PointsRow] = Macro.namedParser[PointsRow](ColumnNaming.SnakeCase)
}
