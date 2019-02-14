package models

import java.sql.Timestamp
import java.text.SimpleDateFormat

import org.squeryl.KeyedEntity
import play.api.libs.json._
import utils.Formatter.timestampFormatFactory

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
//  implicit val timestampFormat = new Format[Timestamp]{
//    val format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
//    // how just super reads?
//    def reads(json: JsValue): JsResult[Timestamp] = JsSuccess(new Timestamp(format.parse(json.as[String]).getTime))
//    def writes(ts: Timestamp) = JsString(format.format(ts))
//  }
  implicit val timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")

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