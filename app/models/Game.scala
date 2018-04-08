package models

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.KeyedEntity

class Game(
            val name: String,
            val code: String,
            var pickee: String, //i.e. Hero, champion, player
          ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class HallOfFame(
                  val gameId: Int,
                  val leagueId: Int,
                  var winnerUserId: Int,
                  var runnerUpUserId: Int
                ) extends KeyedEntity[Int] {
  val id: Int = 0
}