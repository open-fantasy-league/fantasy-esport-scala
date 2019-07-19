package models

import java.time.LocalDateTime

import play.api.libs.json.{JsValue, Json, Writes}
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class TransferRow(
                        transferId: Long, userId: Long, internalPickeeId: Long, externalPickeeId: Long,
                        pickeeName: String, isBuy: Boolean, timeMade: LocalDateTime,
                        price: BigDecimal, wasWildcard: Boolean
              )

object TransferRow{
  implicit val implicitWrites = new Writes[TransferRow] {
    def writes(t: TransferRow): JsValue = {
      Json.obj(
        "isBuy" -> t.isBuy,
        "timeMade" -> t.timeMade,
        "price" -> t.price,
        "wasWildcard" -> t.wasWildcard,
        "pickeeId" -> t.externalPickeeId,
        "pickeeName" -> t.pickeeName
      )
    }
  }

  val parser: RowParser[TransferRow] = Macro.namedParser[TransferRow](ColumnNaming.SnakeCase)
}
case class ChangeOut(pickeeId: Long, cardId: Long)

object ChangeOut{
  implicit val implicitWrites = new Writes[ChangeOut] {
    def writes(t: ChangeOut): JsValue = {
      Json.obj(
        "id" -> t.pickeeId,
        "cardId" -> t.cardId
      )
    }
  }
}
case class ScheduledChangesOut(toBuy: Iterable[ChangeOut], toSell: Iterable[ChangeOut])

object ScheduledChangesOut{
  implicit val implicitWrites = new Writes[ScheduledChangesOut] {
    def writes(t: ScheduledChangesOut): JsValue = {
      Json.obj(
        "toBuy" -> t.toBuy,
        "toSell" -> t.toSell
      )
    }
  }
}