package models

import java.time.LocalDateTime
import java.text.SimpleDateFormat

import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
//import utils.Formatter.timestampFormatFactory

case class MatchRow( // because match is an sql keyword
              externalMatchId: Long, // this is the dota2 match id field
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

object MatchRow{

  implicit val implicitWrites = new Writes[MatchRow] {
    def writes(m: MatchRow): JsValue = {
      Json.obj(
        "startTime" -> m.startTstamp,
        "addedTime" -> m.addedDBTstamp,
        "targetedAtTime" -> m.targetedAtTstamp,
        "tournamentId" -> m.tournamentId,
        "matchId" -> m.externalMatchId,
        "teamOne" -> m.teamOne,
        "teamTwo" -> m.teamTwo,
        "teamOneVictory" -> m.teamOneVictory,
      )
    }
  }

  val parser: RowParser[MatchRow] = Macro.namedParser[MatchRow](ColumnNaming.SnakeCase)
}