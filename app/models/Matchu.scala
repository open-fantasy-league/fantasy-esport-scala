package models

import java.sql.Timestamp

import org.squeryl.KeyedEntity
import play.api.libs.json.{JsValue, Json, Writes}

class Matchu( // because match is an sql keyword
              val leagueId: Long,
              val externalId: Long, // this is the dota2 match id field
              // we dont want to have 2 different games where they can overlap primary key. so dont use match id as primary key
              val period: Int,
              var tournamentId: Long, // for displaying link to tournament page. tournament can differ from league
              var teamOne: String,
              var teamTwo: String,
              var teamOneVictory: Boolean,
              val startTstamp: Timestamp,
              val addedTstamp: Timestamp,
            )
  extends KeyedEntity[Long] {
  val id: Long = 0
}

object Matchu{
  implicit val implicitWrites = new Writes[Matchu] {
    def writes(m: Matchu): JsValue = {
      Json.obj(
        "startTime" -> m.startTstamp,
        "addedTime" -> m.addedTstamp,
        "tournamentId" -> m.tournamentId,
        "id" -> m.externalId,
        "teamOne" -> m.teamOne,
        "teamTwo" -> m.teamTwo,
        "teamOneVictory" -> m.teamOneVictory,
      )
    }
  }
}