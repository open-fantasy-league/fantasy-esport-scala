package models

import java.util.UUID.randomUUID
import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class ApiUserRow(key: String, name: String, email: String, role: Int)

object ApiUserRow{
  implicit val implicitWrites = new Writes[ApiUserRow] {
    def writes(x: ApiUserRow): JsValue = {
      Json.obj(
        "key" -> x.key,
        "name" -> x.name,
        "email" -> x.email
      )
    }
  }

  val parser: RowParser[ApiUserRow] = Macro.namedParser[ApiUserRow](ColumnNaming.SnakeCase)
}
