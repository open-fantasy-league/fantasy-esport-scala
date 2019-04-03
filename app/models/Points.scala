package models

import org.squeryl.KeyedEntity
import play.api.libs.json.{JsValue, Json, Writes}
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class PointsRow(id: Long, resultId: Long, pointsFieldId: Long, value: Double)

class Points(
              val resultId: Long,
              val pointsFieldId: Long,
              var value: Double
            ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val result = AppDB.resultToPoints.right(this)
  lazy val statField = AppDB.statFieldToPoints.right(this)
}

object Points{
  implicit val implicitWrites = new Writes[Points] {
    def writes(p: Points): JsValue = {
      Json.obj(
        p.statField.single.name -> p.value
      )
    }
  }
}

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
