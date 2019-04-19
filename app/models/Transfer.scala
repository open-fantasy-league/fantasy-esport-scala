package models

import java.time.LocalDateTime

import play.api.libs.json.{JsValue, Json, Writes}
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class TransferRow(
                        transferId: Long, leagueUserId: Long, pickeeId: Long, externalPickeeId: Long,
                        pickeeName: String, isBuy: Boolean, timeMade: LocalDateTime,
                        scheduledFor: LocalDateTime, processed: Boolean, price: BigDecimal, wasWildcard: Boolean
              )

object TransferRow{
  //implicit val timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")
  implicit val implicitWrites = new Writes[TransferRow] {
    def writes(t: TransferRow): JsValue = {
      Json.obj(
        "isBuy" -> t.isBuy,
        "timeMade" -> t.timeMade,
        "scheduledFor" -> t.scheduledFor,
        "processed" -> t.processed,
        "price" -> t.price,
        "wasWildcard" -> t.wasWildcard,
        "pickeeId" -> t.externalPickeeId,
        "pickeeName" -> t.pickeeName
      )
    }
  }

  val parser: RowParser[TransferRow] = Macro.namedParser[TransferRow](ColumnNaming.SnakeCase)
}