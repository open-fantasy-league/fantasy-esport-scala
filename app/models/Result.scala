package models

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.KeyedEntity
import java.sql.Timestamp

class Resultu(  // Result is play/scala keyword. renaming makes things simpler/more obvious
              val matchId: Long,
              val pickeeId: Long,
              val startTstamp: Timestamp,
              val addedTstamp: Timestamp,
              var isTeamOne: Boolean, // for showing results
              val appliedPickee: Boolean = false,
              val appliedUser: Boolean = false,
              // maybe want a field that stores points for results.
              // rather than having to sum points matches every time want to show match results.
            ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class PointsField(
                   var name: String,
                   val leagueId: Int,
                   // let the field be either a boolean 0/1 such as pick. or an int/double such as camps stacked or gpm
                   var multiplier: Double,
                 ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Points(
              val resultId: Long,
              val pointsFieldId: Long,
              var value: BigDecimal
            ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Matchu( // because match is an sql keyword
              val leagueId: Int,
              val identifier: Int, // this is the dota2 match id field
              // we dont want to have 2 different games where they can overlap primary key. so dont use match id as primary key
              val day: Int,
              var tournamentId: Int, // for displaying link to tournament page. tournament can differ from league
              var teamOne: String,
              var teamTwo: String,
              var teamOneVictory: Boolean
            )
  extends KeyedEntity[Long] {
  val id: Long = 0
}