package models

import org.squeryl.KeyedEntity
import java.util.UUID.randomUUID

class APIUser(
               var name: String,
               var email: String,
               var role: Int = 0 // TODO this should be enum
             ) extends KeyedEntity[String] {
  val id: String = "A"//randomUUID().toString
  def key = id
}
