package models

import java.time.LocalDateTime
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import play.api.libs.json._

case class StatDailyRow(
                                   statFieldId: Long, statFieldName: String, previousRank: Int, value: Double,
                                   period: Option[Int]
                                 )

case class PickeeRow(internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal)

case class CardBonusMultiplierRow(statFieldId: Long, statFieldName: String, multiplier: Double)

case class CardWithBonusRow(cardId: Long, internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal, colour: String,
                   statFieldId: Option[Long], statFieldName: Option[String], multiplier: Option[Double])

case class CardWithBonusRowAndLimits(cardId: Long, internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal, colour: String,
                            statFieldId: Option[Long], statFieldName: Option[String], multiplier: Option[Double],
                                     limitName: Option[String], limitTypeName: Option[String])

object CardWithBonusRow{
  val parser: RowParser[CardWithBonusRow] = Macro.namedParser[CardWithBonusRow](ColumnNaming.SnakeCase)
}

object CardWithBonusRowAndLimits{
  val parser: RowParser[CardWithBonusRowAndLimits] = Macro.namedParser[CardWithBonusRowAndLimits](ColumnNaming.SnakeCase)
}

object CardBonusMultiplierRow{
  implicit val implicitWrites = new Writes[CardBonusMultiplierRow] {
    def writes(t: CardBonusMultiplierRow): JsValue = {
      Json.obj(
        // TODO conditionally dont print colour/price if/not-if card
        "statFieldId" -> t.statFieldId,
        "name" -> t.statFieldName,
        "multiplier" -> t.multiplier,
      )
    }
  }
}

case class CardOut(cardId: Long, internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal, colour: String,
                   bonuses: Iterable[CardBonusMultiplierRow], limits: Map[String, String])

object CardOut{
    implicit val implicitWrites = new Writes[CardOut] {
      def writes(t: CardOut): JsValue = {
        Json.obj(
          // TODO conditionally dont print colour/price if/not-if card
          "cardId" -> t.cardId,
          "name" -> t.pickeeName,
          "pickeeId" -> t.externalPickeeId,
          "price" -> t.price,
          "colour" -> t.colour,
          "pickeeId" -> t.externalPickeeId,
          "bonuses" -> t.bonuses,
          "limitTypes" -> t.limits
        )
      }
    }
}

case class PickeeLimitsRow(internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal, limitType: String, limitName: String, max: Int)

case class PickeeLimitsAndStatsRow(
                                    internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal, limitType: Option[String],
                                    limitName: Option[String], max: Option[Int], statFieldName: String, value: Double, previousRank: Int)
case class CardRow(cardId: Long, userId: Long, pickeeId: Long, colour: String)

object CardRow{
  val parser: RowParser[CardRow] = Macro.namedParser[CardRow](ColumnNaming.SnakeCase)
}


object PickeeRow {
  implicit val implicitWrites = new Writes[PickeeRow] {
    def writes(x: PickeeRow): JsValue = {
      Json.obj(
        "id" -> x.externalPickeeId,
        "name" -> x.pickeeName,
        "price" -> x.price
      )
    }
  }

  val parser: RowParser[PickeeRow] = Macro.namedParser[PickeeRow](ColumnNaming.SnakeCase)
}

case class TeamRow(externalUserId: Long, username: String, userId: Long, start: Option[Int],
                   end: Option[Int], isActive: Boolean, cardId: Long, internalPickeeId: Long,
                   externalPickeeId: Long, pickeeName: String,
                   pickeePrice: BigDecimal, colour: String,
                   statFieldId: Option[Long], statFieldName: Option[String], multiplier: Option[Double],
                   limitName: Option[String], limitTypeName: Option[String]
                  )


object TeamRow {
//  implicit val implicitWrites = new Writes[TeamRow] {
//    def writes(x: TeamRow): JsValue = {
//      Json.obj(
//        "userId" -> x.externalUserId,
//        "username" -> x.username,
//        "userId" -> x.userId,
//        "start" -> x.start,
//        "end" -> x.end,
//        "isActive" -> x.isActive,
//        "pickeeId" -> x.externalPickeeId,
//        "name" -> x.name,
//        "price" -> x.price
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
        "name" -> p.pickee.pickeeName,
        "stats" -> p.stats,
        "limits" -> p.limits,
        "price" -> p.pickee.price,
        "active" -> true //p.pickee.active  TODO reimplement active
      )
    }
  }
}