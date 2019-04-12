package models

import play.api.libs.json._

class Game(
            val name: String,
            val code: String,
            val variant: String,
            val description: String
          ) extends KeyedEntity[Long] {
  val id: Long = 0
}

object Game{
  implicit val implicitWrites = new Writes[Game] {
    def writes(g: Game): JsValue = {
      Json.obj(
        "name" -> g.name,
        "code" -> g.code,
        "variant" -> g.variant,
        "description" -> g.description
      )
    }
  }
}
