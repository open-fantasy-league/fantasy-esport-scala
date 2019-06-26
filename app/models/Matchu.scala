package models

import java.time.LocalDateTime

import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class SeriesRow(
                      externalSeriesId: Long,
                      period: Int,
                      tournamentId: Long, // for displaying link to tournament page. tournament can differ from league
                      teamOne: String,
                      teamTwo: String,
                      seriesTeamOneFinalScore: Option[Int],
                      seriesTeamTwoFinalScore: Option[Int],
                      startTstamp: LocalDateTime,
                    )

object SeriesRow{

  implicit val implicitWrites = new Writes[SeriesRow] {
    def writes(m: SeriesRow): JsValue = {
      val started = m.startTstamp.isBefore(LocalDateTime.now())
      Json.obj(
        "startTime" -> m.startTstamp,
        "started" -> started,
        "tournamentId" -> m.tournamentId,
        "seriesId" -> m.externalSeriesId,
        "teamOne" -> m.teamOne,
        "teamTwo" -> m.teamTwo,
        "seriesTeamOneFinalScore" -> m.seriesTeamOneFinalScore,
        "seriesTeamTwoFinalScore" -> m.seriesTeamTwoFinalScore,
      )
    }
  }

  val parser: RowParser[MatchRow] = Macro.namedParser[MatchRow](ColumnNaming.SnakeCase)
}

case class MatchRow(
              externalMatchId: Long,
                     matchTeamOneFinalScore: Option[Int],
                     matchTeamTwoFinalScore: Option[Int],
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
        "matchId" -> m.externalMatchId,
        // adding match prefix seems redundant, but avoids annoying bugs where accidentally copy paste series rather than match
        // and dont realise as score attrs the same
        "matchTeamOneFinalScore" -> m.matchTeamOneFinalScore,
        "matchTeamTwoFinalScore" -> m.matchTeamTwoFinalScore
      )
    }
  }

  val parser: RowParser[MatchRow] = Macro.namedParser[MatchRow](ColumnNaming.SnakeCase)
}

case class SeriesAndMatchRow(
                              externalSeriesId: Long,
                              period: Int,
                              tournamentId: Long, // for displaying link to tournament page. tournament can differ from league
                              teamOne: String,
                              teamTwo: String,
                              seriesTeamOneFinalScore: Option[Int],
                              seriesTeamTwoFinalScore: Option[Int],
                              seriesStartTstamp: LocalDateTime,
                              externalMatchId: Option[Long],
                              matchTeamOneFinalScore: Option[Int],
                              matchTeamTwoFinalScore: Option[Int],
                              startTstamp: Option[LocalDateTime],
                              addedDbTstamp: Option[LocalDateTime],
                              targetedAtTstamp: Option[LocalDateTime] // what timestamp do we look up teams for

                            )

object SeriesAndMatchRow{

  def out(rows: Iterable[SeriesAndMatchRow]): Iterable[SeriesOut] = {
    rows.groupBy(_.externalSeriesId).map({case (externalSeriesId, rows) => {
      val head = rows.head
      SeriesOut(SeriesRow(
        externalSeriesId, head.period,head.tournamentId, head.teamOne, head.teamTwo,
        head.seriesTeamOneFinalScore,
        head.seriesTeamTwoFinalScore,
        head.seriesStartTstamp), rows.withFilter(_.externalMatchId.isDefined).map(row => MatchOut(
        MatchRow(
        row.externalMatchId.get, row.matchTeamOneFinalScore,
        row.matchTeamTwoFinalScore,
        row.startTstamp.get,
        row.addedDbTstamp.get,
        row.targetedAtTstamp.get), List[SingleResult]())))
    }})
  }

  val parser: RowParser[SeriesAndMatchRow] = Macro.namedParser[SeriesAndMatchRow](ColumnNaming.SnakeCase)
}

case class SingleResult(isTeamOne: Boolean, pickeeName: String, results: Map[String, Double])
object SingleResult{
  implicit val implicitWrites = new Writes[SingleResult]{
    def writes(r: SingleResult): JsValue = {
      Json.obj(
        "isTeamOne" -> r.isTeamOne,
        "pickee" -> r.pickeeName,
        "stats" -> r.results,
      )
    }
  }
}

case class MatchOut(matchu: MatchRow, results: Iterable[SingleResult])
object MatchOut{
  implicit val implicitWrites = new Writes[MatchOut] {
    def writes(r: MatchOut): JsValue = {
      Json.obj(
        "match" -> r.matchu,
        "results" -> r.results,
      )
    }
  }
}

case class SeriesOut(series: SeriesRow, matches: Iterable[MatchOut])
object SeriesOut{
  implicit val implicitWrites = new Writes[SeriesOut] {
    def writes(r: SeriesOut): JsValue = {
      Json.obj(
        "series" -> r.series,
        "matches" -> r.matches,
      )
    }
  }
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