package models

import org.squeryl.KeyedEntity
import play.api.libs.json.{JsValue, Json, Writes}

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
