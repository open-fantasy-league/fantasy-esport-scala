package models

import org.squeryl.KeyedEntity

class Game(
            val name: String,
            val code: String,
          ) extends KeyedEntity[Int] {
  val id: Int = 0
}