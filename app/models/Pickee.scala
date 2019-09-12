package models

import anorm.{Macro, RowParser}
import Macro.ColumnNaming
import play.api.libs.json._
import utils.Utils

case class StatDailyRow(
                                   statFieldId: Long, statFieldName: String, previousRank: Int, value: Double,
                                   period: Option[Int]
                                 )

case class PickeeRow(internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal, active: Boolean)

case class CardBonusMultiplierRow(statFieldId: Long, statFieldName: String, multiplier: Double, statFieldDescription: Option[String]=None)

case class CardWithBonusRow(cardId: Long, internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal, colour: String,
                   statFieldId: Option[Long], statFieldName: Option[String], multiplier: Option[Double], statFieldDescription: Option[String]=None)

case class CardWithBonusRowAndLimits(cardId: Long, internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal, colour: String,
                            statFieldId: Option[Long], statFieldName: Option[String], multiplier: Option[Double],
                                     limitName: Option[String], limitTypeName: Option[String], statFieldDescription: Option[String]=None)

case class CardWithBonusRowAndLimitsAndStats(cardId: Long, internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal, colour: String,
                                     statFieldId: Option[Long], statFieldName: Option[String], multiplier: Option[Double],
                                     limitName: Option[String], limitTypeName: Option[String], statFieldName2: Option[String],
                                             period: Option[Int], value: Option[Double], statFieldDescription: Option[String]=None)

object CardWithBonusRow{
  val parser: RowParser[CardWithBonusRow] = Macro.namedParser[CardWithBonusRow](ColumnNaming.SnakeCase)
}

object CardWithBonusRowAndLimits{
  val parser: RowParser[CardWithBonusRowAndLimits] = Macro.namedParser[CardWithBonusRowAndLimits](ColumnNaming.SnakeCase)
}

object CardWithBonusRowAndLimitsAndStats{
  val parser: RowParser[CardWithBonusRowAndLimitsAndStats] = Macro.namedParser[CardWithBonusRowAndLimitsAndStats](ColumnNaming.SnakeCase)
}

object CardBonusMultiplierRow{
  implicit val implicitWrites = new Writes[CardBonusMultiplierRow] {
    def writes(t: CardBonusMultiplierRow): JsValue = {
      Json.obj(
        // TODO conditionally dont print colour/price if/not-if card
        "statFieldId" -> t.statFieldId,
        "name" -> t.statFieldName,
        "description" -> t.statFieldDescription,
        "multiplier" ->  Utils.trunc(t.multiplier, 2),
      )
    }
  }
}

case class CardOut(cardId: Long, internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal, colour: String,
                   bonuses: Iterable[CardBonusMultiplierRow], limits: Map[String, String],
                   overallStats: Map[String, Double] = Map(), recentPeriodStats: Map[Int, Map[String, Double]] = Map())

// necessary as json doesnt allow numerical dict keys
case class recentPeriodStats(period: Int, stats: Map[String, Double])
object recentPeriodStats{
  implicit val implicitWrites = new Writes[recentPeriodStats] {
    def writes(t: recentPeriodStats): JsValue = {
      Json.obj(
        "period" -> t.period,
        "stats" -> t.stats.mapValues(Utils.trunc(_, 1))
      )
    }
  }
}

object CardOut{
    implicit val implicitWrites = new Writes[CardOut] {
      def writes(t: CardOut): JsValue = {
        Json.obj(
          // TODO conditionally dont print colour/price if/not-if card
          "cardId" -> t.cardId,
          "name" -> t.pickeeName,
          "id" -> t.externalPickeeId,
          "price" -> t.price,
          "colour" -> t.colour,
          "bonuses" -> t.bonuses,
          "limitTypes" -> t.limits,
          "overallStats" -> t.overallStats.mapValues(Utils.trunc(_, 1)),
          "recentPeriodStats" -> t.recentPeriodStats.map({case (k, v) => {
            recentPeriodStats(k, v)
          }}).toList
        )
      }
    }
}

case class PickeeLimitsRow(internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal,
                           active: Boolean, limitType: Option[String], limitName: Option[String], max: Option[Int])

case class PickeeLimitsAndStatsRow(
                                    internalPickeeId: Long, externalPickeeId: Long, pickeeName: String, price: BigDecimal,
                                    active: Boolean, limitType: Option[String],
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
        "price" -> x.price,
        "active" -> x.active
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
                   limitName: Option[String], limitTypeName: Option[String], statFieldDescription: Option[String] = None
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
        "stats" -> p.stats.mapValues(Utils.trunc(_, 1)),
        "limitTypes" -> p.limits,
        "price" -> p.pickee.price,
        "active" -> true //p.pickee.active  TODO reimplement active
      )
    }
  }
}

case class DraftWatchlistRow(pickeeName: String, externalPickeeId: Long, pickeeId: Long)

object DraftWatchlistRow{
  implicit val implicitWrites = new Writes[DraftWatchlistRow] {
    def writes(p: DraftWatchlistRow): JsValue = {
      Json.obj(
        "id" -> p.externalPickeeId,
        "name" -> p.pickeeName,
      )
    }
  }
  val parser: RowParser[DraftWatchlistRow] = Macro.namedParser[DraftWatchlistRow](ColumnNaming.SnakeCase)
}

case class DraftOrderRow(username: String, externalUserId: Long, userId: Long)

object DraftOrderRow{
  implicit val implicitWrites = new Writes[DraftOrderRow] {
    def writes(p: DraftOrderRow): JsValue = {
      Json.obj(
        "id" -> p.externalUserId,
        "username" -> p.username,
      )
    }
  }
  val parser: RowParser[DraftOrderRow] = Macro.namedParser[DraftOrderRow](ColumnNaming.SnakeCase)
}