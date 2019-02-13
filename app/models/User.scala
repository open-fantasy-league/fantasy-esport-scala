package models

import org.squeryl.KeyedEntity
import play.api.libs.json._

class User(
            var username: String,
            val externalId: Long,
          ) extends KeyedEntity[Long] {
  val id: Long = 0

  lazy val leagues = AppDB.leagueUserTable.right(this)
}

object User{
  implicit val implicitWrites = new Writes[User] {
    def writes(user: User): JsValue = {
      Json.obj(
        "id" -> user.externalId,
        "username" -> user.username
      )
    }
  }
}
