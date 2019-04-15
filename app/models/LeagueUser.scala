package models

import java.time.LocalDateTime

import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class LeagueUserRow(
                          userId: Long, username: String, externalUserId: Long, leagueUserId: Long, money: BigDecimal,
                          entered: LocalDateTime, remainingTransfers: Option[Int], usedWildcard: Boolean,
                          changeTstamp: Option[LocalDateTime]
                        )

object LeagueUserRow{
  //implicit val timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")
  implicit val implicitWrites = new Writes[LeagueUserRow] {
    def writes(lu: LeagueUserRow): JsValue = {
      Json.obj(
        "userId" -> lu.externalUserId,
        "username" -> lu.username,
        "money" -> lu.money,
        "entered" -> lu.entered,
        "remainingTransfers" -> lu.remainingTransfers,
        "usedWildcard" -> lu.usedWildcard,
        "transferScheduledTime" -> lu.changeTstamp
      )
    }
  }

  val parser: RowParser[LeagueUserRow] = Macro.namedParser[LeagueUserRow](ColumnNaming.SnakeCase)
}

case class LeagueUserStatDailyRow(
                                   leagueUserId: Long, statFieldName: String, previousRank: Int, value: Double,
                                   period: Option[Int]
                                 )

object LeagueUserStatDailyRow{

  val parser: RowParser[LeagueUserStatDailyRow] = Macro.namedParser[LeagueUserStatDailyRow](ColumnNaming.SnakeCase)
}