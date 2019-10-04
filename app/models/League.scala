package models

import java.time.LocalDateTime

import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class DetailedLeagueRow(
                            leagueId: Long,
                             leagueName: String,
                             gameId: Option[Long],
                             isPrivate: Boolean,
                             tournamentId: Option[Long],
                             pickeeDescription: String,
                             periodDescription: String,
                             transferLimit: Option[Int],
                             transferWildcard: Option[Boolean],
                             startingMoney: BigDecimal,
                             teamSize: Int,
                             benchSize: Int,
                             transferOpen: Boolean,
                             forceFullTeams: Boolean,
                             url: String,
                             urlVerified: Boolean,
                             applyPointsAtStartTime: Boolean,
                             noWildcardForLateRegister: Option[Boolean],
                            system: String,
                            recycleValue: Option[BigDecimal],
                            packCost: Option[BigDecimal],
                            packSize: Option[Int],
                            draftStart: Option[LocalDateTime],
                            choiceTimer: Option[Int],
                            nextDraftDeadline: Option[LocalDateTime],
                            manualDraft: Option[Boolean],
                             started: Boolean,
                             ended: Boolean,
                             periodValue: Option[Int],
                             start: Option[LocalDateTime],
                             end: Option[LocalDateTime],
                             multiplier: Option[Double],
                             onStartCloseTransferWindow: Option[Boolean],
                             onEndOpenTransferWindow: Option[Boolean],
                            onEndEliminateUsersTo: Option[Int],
                            currentPeriodId: Option[Long],
                            currentPeriodValue: Option[Int],
                            currentPeriodStart: Option[LocalDateTime],
                            currentPeriodEnd: Option[LocalDateTime],
                            currentPeriodMultiplier: Option[Double],
                            currentPeriodOnStartCloseTransferWindow: Option[Boolean],
                            currentPeriodOnEndOpenTransferWindow: Option[Boolean],
                             statFieldName: Option[String],
                            statFieldDescription: Option[String],
                             limitTypeName: Option[String],
                             description: Option[String],
                             limitName: Option[String],
                             limitMax: Option[Int],
                            limitMaxBench: Option[Int],
                            numPeriods: Int,
                            predictionWinMoney: Option[BigDecimal]
                            )

case class PublicLeagueRow(
                          leagueId: Long,
                          leagueName: String,
                          gameId: Option[Long],
                          isPrivate: Boolean,
                          tournamentId: Option[Long],
                          pickeeDescription: String,
                          periodDescription: String,
                          transferLimit: Option[Int],
                          transferWildcard: Option[Boolean],
                          startingMoney: BigDecimal,
                          teamSize: Int,
                          benchSize: Int,
                          transferOpen: Boolean,
                          forceFullTeams: Boolean,
                          url: String,
                          urlVerified: Boolean,
                          applyPointsAtStartTime: Boolean,
                          noWildcardForLateRegister: Option[Boolean],
                          system: String,
                          recycleValue: Option[BigDecimal],
                          packCost: Option[BigDecimal],
                          packSize: Option[Int],
                          draftStart: Option[LocalDateTime],
                          choiceTimer: Option[Int],
                          nextDraftDeadline: Option[LocalDateTime],
                          manualDraft: Option[Boolean],
                          predictionWinMoney: Option[BigDecimal],
                          started: Boolean,
                          ended: Boolean,
                          numPeriods: Int,
                          currentPeriod: Option[PeriodRow]
)

object PublicLeagueRow{
  //implicit val timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")
  implicit val implicitWrites = new Writes[PublicLeagueRow] {
    def writes(league: PublicLeagueRow): JsValue = {
      Json.obj(
        "id" -> league.leagueId,
        "name" -> league.leagueName,
        "gameId" -> league.gameId,
        "tournamentId" -> league.tournamentId,
        "isPrivate" -> league.isPrivate,
        "pickeeDescription" -> league.pickeeDescription,
        "periodDescription" -> league.periodDescription,
        "teamSize" -> league.teamSize,
        "benchSize" -> league.benchSize,
        "transferLimit" -> league.transferLimit,
        "transferWildcard" -> league.transferWildcard,
        "startingMoney" -> league.startingMoney,
        "transferOpen" -> league.transferOpen,
        "forceFullTeams" -> league.forceFullTeams,
        "applyPointsAtStartTime" -> league.applyPointsAtStartTime,
        "url" -> {if (league.urlVerified) league.url else ""},
        "noWildcardForLateRegister" -> league.noWildcardForLateRegister,
        "system" -> league.system,
        "draftStart" -> league.draftStart,
        "draftChoiceSeconds" -> league.choiceTimer,
        "nextDraftDeadline" -> league.nextDraftDeadline,
        "manualDraft" -> league.manualDraft,
        "recycleValue" -> league.recycleValue,
        "packCost" -> league.packCost,
        "packSize" -> league.packSize,
        "predictionWinMoney" -> league.predictionWinMoney,
        "started" -> league.started,
        "ended" -> league.ended,
        "numPeriods" -> league.numPeriods,
      )
    }
  }

  def fromDetailedRow(row: DetailedLeagueRow): PublicLeagueRow = {
    PublicLeagueRow(
      row.leagueId, row.leagueName, row.gameId, row.isPrivate, row.tournamentId, row.pickeeDescription, row.periodDescription,
      row.transferLimit, row.transferWildcard, row.startingMoney, row.teamSize, row.benchSize,
      row.transferOpen, row.forceFullTeams, row.url, row.urlVerified, row.applyPointsAtStartTime,
      row.noWildcardForLateRegister, row.system, row.recycleValue, row.packCost, row.packSize, row.draftStart, row.choiceTimer,
      row.nextDraftDeadline, row.manualDraft, row.predictionWinMoney, row.started, row.ended, row.numPeriods,
      if (row.currentPeriodId.isDefined) Some(PeriodRow(
        row.currentPeriodId.get, row.leagueId, row.currentPeriodValue.get, row.currentPeriodStart.get, row.currentPeriodEnd.get,
        row.currentPeriodMultiplier.get, row.currentPeriodOnStartCloseTransferWindow.get, row.currentPeriodOnEndOpenTransferWindow.get
      )) else None
    )
  }
}

case class LeagueRow(leagueId: Long,
                     leagueName: String,
                     apiKey: String, // the api user/platform that created the league
                     gameId: Option[Long],
                     isPrivate: Boolean,
                     tournamentId: Option[Long],
                     pickeeDescription: String,
                     periodDescription: String,
                     transferLimit: Option[Int],
                     transferWildcard: Option[Boolean],
                     startingMoney: BigDecimal,
                     teamSize: Int,
                     benchSize: Int,
                     transferOpen: Boolean = false,
                     forceFullTeams: Boolean = false,
                     url: String = "",
                     urlVerified: Boolean = false,
                     currentPeriodId: Option[Long] = None,
                     applyPointsAtStartTime: Boolean = true, // compared to applying at entry time
                     noWildcardForLateRegister: Option[Boolean] = Some(false), // late defined as after league has startd,
                     manuallyCalculatePoints: Boolean = true,
                     system: String = "regular",
                     recycleValue: Option[BigDecimal] = None,
                     packCost: Option[BigDecimal] = None,
                     packSize: Option[Int] = None,
                     predictionWinMoney: Option[BigDecimal] = None,
                     nextDraftDeadline: Option[LocalDateTime] = None,
)

case class LeagueStatFieldRow(statFieldId: Long, leagueId: Long, name: String, description: Option[String])

case class LimitRow(name: String, max: Int)

object LimitRow{
  implicit val implicitWrites = new Writes[LimitRow] {
    def writes(f: LimitRow): JsValue = {
      Json.obj(
        "name" -> f.name,
        "max" -> f.max
      )
    }
  }
}

case class PeriodRow(periodId: Long,
                     leagueId: Long,
                     value: Int,
                     start: LocalDateTime,
                     end: LocalDateTime,
                     multiplier: Double = 1.0,
                     onStartCloseTransferWindow: Boolean = false,
                     onEndOpenTransferWindow: Boolean = false,
                     nextPeriodId: Option[Long] = None,
                     ended: Boolean = false,
                     onEndEliminateUsersTo: Option[Int] = None
                    )

object PeriodRow{
  implicit val implicitWrites = new Writes[PeriodRow] {
    def writes(f: PeriodRow): JsValue = {
      Json.obj(
        "value" -> f.value,
        "start" -> f.start,
        "end" -> f.end,
        "multiplier" -> f.multiplier,
        "onStartCloseTransferWindow" -> f.onStartCloseTransferWindow,
        "onEndOpenTransferWindow" -> f.onEndOpenTransferWindow,
        "onEndEliminateUsersTo" -> f.onEndEliminateUsersTo
      )
    }
  }

  val parser: RowParser[PeriodRow] = Macro.namedParser[PeriodRow](ColumnNaming.SnakeCase)
}

object LeagueRow{
  //TODO update this to current stuff
  //implicit val timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")
  implicit val implicitWrites = new Writes[LeagueRow] {
    def writes(league: LeagueRow): JsValue = {
      Json.obj(
        "id" -> league.leagueId,
        "name" -> league.leagueName,
        "gameId" -> league.gameId,
        "tournamentId" -> league.tournamentId,
        "isPrivate" -> league.isPrivate,
        "pickeeDescription" -> league.pickeeDescription,
        "periodDescription" -> league.periodDescription,
        "teamSize" -> league.teamSize,
        "benchSize" -> league.benchSize,
        "transferLimit" -> league.transferLimit,
        "transferWildcard" -> league.transferWildcard,
        "startingMoney" -> league.startingMoney,
        "transferOpen" -> league.transferOpen,
        "forceFullTeams" -> league.forceFullTeams,
        "applyPointsAtStartTime" -> league.applyPointsAtStartTime,
        "url" -> {if (league.urlVerified) league.url else ""},
        "noWildcardForLateRegister" -> league.noWildcardForLateRegister
      )
    }
  }
}

case class ScoringRow(statFieldName: String, limitName: Option[String], points: Double)

object ScoringRow{

  def rowsToOut(rows: Iterable[ScoringRow]): Map[String, Map[String, Double]] = {
    rows.groupBy(_.statFieldName).mapValues({rows2 => {
      if (rows2.head.limitName.isDefined){
        rows2.map(r => r.limitName.get -> r.points).toMap
      } else{
        Map[String, Double]("any" -> rows2.head.points)
      }
    }
    })
  }

  implicit val implicitWrites = new Writes[ScoringRow] {
    def writes(x: ScoringRow): JsValue = {
      Json.obj(
        "statFieldName" -> x.statFieldName,
        "limitName" -> x.limitName,
        "points" -> x.points,
      )
    }
  }

  val parser: RowParser[ScoringRow] = Macro.namedParser[ScoringRow](ColumnNaming.SnakeCase)
}

