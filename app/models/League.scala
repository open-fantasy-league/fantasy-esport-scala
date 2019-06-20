package models

import java.time.LocalDateTime

import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class DetailedLeagueRow(
                            leagueId: Long,
                             leagueName: String,
                             gameId: Option[Long],
                             isPrivate: Boolean,
                             tournamentId: Long,
                             pickeeDescription: String,
                             periodDescription: String,
                             transferLimit: Option[Int],
                             transferWildcard: Option[Boolean],
                             startingMoney: BigDecimal,
                             teamSize: Int,
                             transferOpen: Boolean,
                             forceFullTeams: Boolean,
                             url: String,
                             urlVerified: Boolean,
                             applyPointsAtStartTime: Boolean,
                             noWildcardForLateRegister: Option[Boolean],
                            isCardSystem: Boolean,
                            recycleValue: Option[BigDecimal],
                            packCost: Option[BigDecimal],
                            packSize: Option[Int],
                             started: Boolean,
                             ended: Boolean,
                             periodValue: Int,
                             start: LocalDateTime,
                             end: LocalDateTime,
                             multiplier: Double,
                             onStartCloseTransferWindow: Boolean,
                             onEndOpenTransferWindow: Boolean,
                             current: Boolean,
                             statFieldName: Option[String],
                             limitTypeName: Option[String],
                             description: Option[String],
                             limitName: Option[String],
                             limitMax: Option[Int],
                            )

case class PublicLeagueRow(
                          leagueId: Long,
                          leagueName: String,
                          gameId: Option[Long],
                          isPrivate: Boolean,
                          tournamentId: Long,
                          pickeeDescription: String,
                          periodDescription: String,
                          transferLimit: Option[Int],
                          transferWildcard: Option[Boolean],
                          startingMoney: BigDecimal,
                          teamSize: Int,
                          transferOpen: Boolean,
                          forceFullTeams: Boolean,
                          url: String,
                          urlVerified: Boolean,
                          applyPointsAtStartTime: Boolean,
                          noWildcardForLateRegister: Option[Boolean],
                          isCardSystem: Boolean,
                          recycleValue: Option[BigDecimal],
                          packCost: Option[BigDecimal],
                          packSize: Option[Int],
                          started: Boolean,
                          ended: Boolean
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
        "transferLimit" -> league.transferLimit,
        "transferWildcard" -> league.transferWildcard,
        "startingMoney" -> league.startingMoney,
        "transferOpen" -> league.transferOpen,
        "forceFullTeams" -> league.forceFullTeams,
        "applyPointsAtStartTime" -> league.applyPointsAtStartTime,
        "url" -> {if (league.urlVerified) league.url else ""},
        "noWildcardForLateRegister" -> league.noWildcardForLateRegister,
        "isCardSystem" -> league.isCardSystem,
        "recycleValue" -> league.recycleValue,
        "packCost" -> league.packCost,
        "packSize" -> league.packSize,
        "started" -> league.started,
        "ended" -> league.ended
      )
    }
  }

  def fromDetailedRow(row: DetailedLeagueRow): PublicLeagueRow = {
    PublicLeagueRow(
      row.leagueId, row.leagueName, row.gameId, row.isPrivate, row.tournamentId, row.pickeeDescription, row.periodDescription,
      row.transferLimit, row.transferWildcard, row.startingMoney, row.teamSize,
      row.transferOpen, row.forceFullTeams, row.url, row.urlVerified, row.applyPointsAtStartTime,
      row.noWildcardForLateRegister, row.isCardSystem, row.recycleValue, row.packCost, row.packSize, row.started, row.ended
    )
  }
}

case class LeagueRow(leagueId: Long,
                     leagueName: String,
                     apiKey: String, // the api user/platform that created the league
                     gameId: Option[Long],
                     isPrivate: Boolean,
                     tournamentId: Long,
                     pickeeDescription: String,
                     periodDescription: String,
                     transferLimit: Option[Int],
                     transferWildcard: Option[Boolean],
                     startingMoney: BigDecimal,
                     teamSize: Int,
                     transferOpen: Boolean = false,
                     forceFullTeams: Boolean = false,
                     url: String = "",
                     urlVerified: Boolean = false,
                     currentPeriodId: Option[Long] = None,
                     applyPointsAtStartTime: Boolean = true, // compared to applying at entry time
                     noWildcardForLateRegister: Option[Boolean] = Some(false), // late defined as after league has startd,
                     manuallyCalculatePoints: Boolean = true,
                     isCardSystem: Boolean = false,
                     recycleValue: Option[BigDecimal] = None,
                     packCost: Option[BigDecimal] = None,
                     packSize: Option[Int] = None
)

case class LeagueStatFieldRow(statFieldId: Long, leagueId: Long, name: String)

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
        "onEndOpenTransferWindow" -> f.onEndOpenTransferWindow
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

