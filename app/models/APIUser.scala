package models

import org.squeryl.KeyedEntity
import java.util.UUID.randomUUID
import play.api.libs.json._

class APIUser(
               var name: String,
               var email: String,
               var role: Int = 0 // TODO this should be enum
             ) extends KeyedEntity[String] {
  val id: String = randomUUID().toString
  def key: String = id
}

object APIUser{
  implicit val implicitWrites = new Writes[APIUser] {
    def writes(x: APIUser): JsValue = {
      Json.obj(
        "key" -> x.key,
        "name" -> x.name,
        "email" -> x.email
      )
    }
  }
}
