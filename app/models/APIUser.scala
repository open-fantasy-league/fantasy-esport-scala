package models

import org.squeryl.KeyedEntity

class APIUser(
               var name: String,
               var apikey: String,
               var email: String,
               var role: Int // TODO this should be enum
             ) extends KeyedEntity[Long] {
  val id: Long = 0
}