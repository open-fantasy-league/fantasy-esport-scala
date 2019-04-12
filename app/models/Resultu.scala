package models

import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

object ResultRow{
  implicit val implicitWrites = new Writes[ResultRow] {
    def writes(r: ResultRow): JsValue = {
      Json.obj(
        "id" -> r.matchId, // TODO fix
        "isTeamOne" -> r.isTeamOne,
        "pickee" -> "cat"//r.pickee.single
      )
    }
  }

  val parser: RowParser[ResultRow] = Macro.namedParser[ResultRow](ColumnNaming.SnakeCase)
}
