package models

import java.sql.Timestamp

import org.squeryl.KeyedEntity
import play.api.libs.json._

class User(
            var username: String,
            val externalId: Option[Long],
          ) extends KeyedEntity[Int] {
  val id: Int = 0

  // If a class has an Option[] field, it becomes mandatory
  // to implement a zero argument constructor
  def this() = this("", None)

  lazy val leagues = AppDB.leagueUserTable.right(this)
}

object User{
  implicit val implicitWrites = new Writes[User] {
    def writes(user: User): JsValue = {
      Json.obj(
        "id" -> user.id,
        "externalId" -> user.externalId,
        "username" -> user.username
      )
    }
  }
}

class Friend(
              val userId: Int,
              val friendId: Int
            ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Transfer(
                val leagueUserId: Long,
                val pickeeId: Long,
                val isBuy: Boolean,
                val scheduledFor: Timestamp,
                var processed: Boolean,
                val cost: Double
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
        "cost" -> t.cost,
        "internalPickeeId" -> t.pickeeId,
        "externalPickeeId" -> t.pickee.externalId,
        "pickeeName" -> t.pickee.name
      )
    }
  }
}