package models

import java.time.LocalDateTime

import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class UserRow(
                          userId: Long, username: String, externalUserId: Long, money: BigDecimal,
                          entered: LocalDateTime, remainingTransfers: Option[Int], usedWildcard: Boolean,
                          changeTstamp: Option[LocalDateTime]
                        )

object UserRow{
  //implicit val timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")
  implicit val implicitWrites = new Writes[UserRow] {
    def writes(u: UserRow): JsValue = {
      Json.obj(
        "userId" -> u.externalUserId,
        "username" -> u.username,
        "money" -> u.money,
        "entered" -> u.entered,
        "remainingTransfers" -> u.remainingTransfers,
        "usedWildcard" -> u.usedWildcard,
        "transferScheduledTime" -> u.changeTstamp
      )
    }
  }

  val parser: RowParser[UserRow] = Macro.namedParser[UserRow](ColumnNaming.SnakeCase)
}

case class UserStatDailyRow(
                                   userId: Long, statFieldName: String, previousRank: Int, value: Double,
                                   period: Option[Int]
                                 )

object UserStatDailyRow{

  val parser: RowParser[UserStatDailyRow] = Macro.namedParser[UserStatDailyRow](ColumnNaming.SnakeCase)
}