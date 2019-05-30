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
              teamOneVictory: Option[Boolean],
                     teamOneScore: Option[Int],
                     teamTwoScore: Option[Int],
              startTstamp: LocalDateTime,
              addedDbTstamp: LocalDateTime,
              targetedAtTstamp: LocalDateTime // what timestamp do we look up teams for
            )

object MatchRow{

  implicit val implicitWrites = new Writes[MatchRow] {
    def writes(m: MatchRow): JsValue = {
      val started = m.startTstamp.isBefore(LocalDateTime.now())
      Json.obj(
        "startTime" -> m.startTstamp,
        "started" -> started,
        "addedTime" -> m.addedDbTstamp,
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

case class PredictionRow(externalMatchId: Long, teamOneScore: Int, teamTwoScore: Int, userId: Long, paidOut: Boolean)

object PredictionRow{

  implicit val implicitWrites = new Writes[PredictionRow] {
    def writes(x: PredictionRow): JsValue = {
      Json.obj(
        "matchId" -> x.externalMatchId,
        "teamOneScore" -> x.teamOneScore,
        "teamTwoScore" -> x.teamTwoScore,
        "userId" -> x.userId,
        "paidOut" -> x.paidOut
      )
    }
  }

  val parser: RowParser[PredictionRow] = Macro.namedParser[PredictionRow](ColumnNaming.SnakeCase)
}