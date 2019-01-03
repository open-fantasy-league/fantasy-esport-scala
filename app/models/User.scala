package models

import java.sql.Timestamp

import org.squeryl.KeyedEntity
import play.api.libs.json._
import utils.CostConverter

class User(
            var username: String,
            val externalId: Option[Long],
          ) extends KeyedEntity[Long] {
  val id: Long = 0

  // If a class has an Option[] field, it becomes mandatory
  // to implement a zero argument constructor
  def this() = this("", None)

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

class Transfer(
                val leagueUserId: Long,
                val pickeeId: Long,
                val isBuy: Boolean,
                val scheduledFor: Timestamp,
                var processed: Boolean,
                val cost: Int,
                val wasWildcard: Boolean = false
) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val pickee = AppDB.pickeeToTransfer.right(this).single
}

object Transfer{
  implicit val implicitWrites = new Writes[Transfer] {
    def writes(t: Transfer): JsValue = {
      Json.obj(
        "isBuy" -> t.isBuy,
        "scheduledFor" -> t.scheduledFor,
        "processed" -> t.processed,
        "cost" -> CostConverter.convertCost(t.cost),
        "wasWildcard" -> t.wasWildcard,
        "pickeeId" -> t.pickee.externalId,
        "pickeeName" -> t.pickee.name
      )
    }
  }
}
