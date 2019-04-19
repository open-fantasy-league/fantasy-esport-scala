package models

import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class UserRow(userId: Long, username: String, externalUserId: Long)

object UserRow{
  implicit val implicitWrites = new Writes[UserRow] {
    def writes(user: UserRow): JsValue = {
      Json.obj(
        "id" -> user.externalUserId,
        "username" -> user.username
      )
    }
  }

  val parser: RowParser[UserRow] = Macro.namedParser[UserRow](ColumnNaming.SnakeCase)
}
