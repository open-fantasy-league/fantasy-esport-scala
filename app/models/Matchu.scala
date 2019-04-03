package models

import java.time.LocalDateTime
import java.text.SimpleDateFormat

import org.squeryl.KeyedEntity
import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
//import utils.Formatter.timestampFormatFactory

case class MatchRow( // because match is an sql keyword
                   id: Long,
              leagueId: Long,
              externalId: Long, // this is the dota2 match id field
              // we dont want to have 2 different games where they can overlap primary key. so dont use match id as primary key
              period: Int,
              tournamentId: Long, // for displaying link to tournament page. tournament can differ from league
              teamOne: String,
              teamTwo: String,
              teamOneVictory: Boolean,
              startTstamp: LocalDateTime,
              addedDBTstamp: LocalDateTime,
              targetedAtTstamp: LocalDateTime // what timestamp do we look up teams for
            )

class Matchu( // because match is an sql keyword
              val leagueId: Long,
              val externalId: Long, // this is the dota2 match id field
              // we dont want to have 2 different games where they can overlap primary key. so dont use match id as primary key
              val period: Int,
              var tournamentId: Long, // for displaying link to tournament page. tournament can differ from league
              var teamOne: String,
              var teamTwo: String,
              var teamOneVictory: Boolean,
              val startTstamp: LocalDateTime,
              val addedDBTstamp: LocalDateTime,
              val targetedAtTstamp: LocalDateTime // what timestamp do we look up teams for
            )
  extends KeyedEntity[Long] {
  val id: Long = 0
}

object Matchu{
//  implicit val timestampFormat = new Format[LocalDateTime]{
//    val format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
//    // how just super reads?
//    def reads(json: JsValue): JsResult[LocalDateTime] = JsSuccess(new LocalDateTime(format.parse(json.as[String]).getTime))
//    def writes(ts: LocalDateTime) = JsString(format.format(ts))
//  }
  //implicit val timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")

  implicit val implicitWrites = new Writes[Matchu] {
    def writes(m: Matchu): JsValue = {
      Json.obj(
        "startTime" -> m.startTstamp,
        "addedTime" -> m.addedDBTstamp,
        "targetedAtTime" -> m.targetedAtTstamp,
        "tournamentId" -> m.tournamentId,
        "id" -> m.externalId,
        "teamOne" -> m.teamOne,
        "teamTwo" -> m.teamTwo,
        "teamOneVictory" -> m.teamOneVictory,
      )
    }
  }
}

object MatchRow{

  implicit val implicitWrites = new Writes[MatchRow] {
    def writes(m: MatchRow): JsValue = {
      Json.obj(
        "startTime" -> m.startTstamp,
        "addedTime" -> m.addedDBTstamp,
        "targetedAtTime" -> m.targetedAtTstamp,
        "tournamentId" -> m.tournamentId,
        "id" -> m.externalId,
        "teamOne" -> m.teamOne,
        "teamTwo" -> m.teamTwo,
        "teamOneVictory" -> m.teamOneVictory,
      )
    }
  }

  val parser: RowParser[MatchRow] = Macro.namedParser[MatchRow](ColumnNaming.SnakeCase)
}