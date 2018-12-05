package models

import org.squeryl.KeyedEntity

class Game(
            val name: String,
            val code: String,
          ) extends KeyedEntity[Long] {
  val id: Long = 0
}