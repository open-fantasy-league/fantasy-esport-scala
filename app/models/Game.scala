package models

import play.api.libs.json._

class GameRow(
            val gameName: String,
            val code: String,
            val variant: String,
            val description: String
          ) extends KeyedEntity[Long] {
  val id: Long = 0
}

object GameRow{
  implicit val implicitWrites = new Writes[GameRow] {
    def writes(g: GameRow): JsValue = {
      Json.obj(
        "name" -> g.gameName,
        "code" -> g.code,
        "variant" -> g.variant,
        "description" -> g.description
      )
    }
  }
}
