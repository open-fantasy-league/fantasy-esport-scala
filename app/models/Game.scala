package models

import anorm.{Macro, RowParser}
import anorm.Macro.ColumnNaming
import play.api.libs.json._

case class GameRow(gameId: Long, gameName: String, code: String, variant: String, description: String)

object GameRow{
  implicit val implicitWrites = new Writes[GameRow] {
    def writes(g: GameRow): JsValue = {
      Json.obj(
        "id" -> g.gameId,
        "name" -> g.gameName,
        "code" -> g.code,
        "variant" -> g.variant,
        "description" -> g.description
      )
    }
  }

  val parser: RowParser[GameRow] = Macro.namedParser[GameRow](ColumnNaming.SnakeCase)
}
