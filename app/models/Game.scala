package models

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.KeyedEntity

class Game(
            val name: String,
            val code: String,
          ) extends KeyedEntity[Int] {
  val id: Int = 0
}

//class HallOfFame(
//                  val gameId: Int,
//                  val leagueId: Int,
//                  var winnerUserId: Int,
//                  var runnerUpUserId: Int
//                ) extends KeyedEntity[Int] {
//  val id: Int = 0
//}