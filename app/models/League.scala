package models

import java.sql.Timestamp

import play.api.libs.json._
import utils.Formatter.timestampFormatFactory

case class League(
                   leagueId: Long, name: String, apiKey: String, // the api user/platform that created the league
                   gameId: Option[Long], isPrivate: Boolean, tournamentId: Long, pickeeDescription: String,
                   periodDescription: String, transferLimit: Option[Int], transferWildcard: Boolean,
                   startingMoney: BigDecimal, teamSize: Int, transferDelayMinutes: Int, // Only applies for when period 1 has started
                   transferOpen: Boolean, transferBlockedDuringPeriod: Boolean, url: String, urlVerified: Boolean,
                   currentPeriodId: Option[Long], applyPointsAtStartTime: Boolean, // compared to applying at entry time
                   noWildcardForLateRegister // late defined as after league has startd
            )

case class LeagueStatField(leagueStatFieldId: Long, leagueId: Long, name: String)
case class LeaguePrize(leaguePrizeId: Long, leagueId: Long, description: String, email: String)

case class LimitType(limitTypeId: Long, leagueId: Long, name: String, description: String, max: Option[Int] = None)

case class LimitTypeOut(name: String, description: String, limits: Iterable[Limit])


case class Limit(limitId: Long, limitTypeId: Long, name: String, max: Int)

case class Period(
                   periodId: Long, leagueId: Long, value: Int, start: Timestamp, end: Timestamp, multiplier: Double,
                   nextPeriodId: Option[Long], ended: Boolean
                 )


object Limit{
  implicit implicitWrites = new Writes[Limit] {
    def writes(f: Limit): JsValue = {
      Json.obj(
        "name" -> f.name,
        "max" -> f.max
      )
    }
  }
}

object LimitTypeOut{
  implicit implicitWrites = new Writes[LimitTypeOut] {
    def writes(ft: LimitTypeOut): JsValue = {
      Json.obj(
        "name" -> ft.name,
        "description" -> ft.description,
        "limits" -> ft.limits
      )
    }
  }
}


object LeaguePrize{
  implicit implicitWrites = new Writes[LeaguePrize] {
    def writes(lp: LeaguePrize): JsValue = {
      Json.obj(
        "description" -> lp.description,
        "email" -> lp.email
      )
    }
  }
}

object Period{
  implicit timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")
  implicit implicitWrites = new Writes[Period] {
    def writes(p: Period): JsValue = {
      Json.obj(
        "value" -> p.value,
        "start" -> p.start,
        "end" -> p.end,
        "multiplier" -> p.multiplier
      )
    }
  }
}

object LimitType{
  implicit implicitWrites = new Writes[LimitType] {
    def writes(ft: LimitType): JsValue = {
      Json.obj(
        "name" -> ft.name,
        "description" -> ft.description,
      )
    }
  }
}

object League{
  implicit timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")
  implicit implicitWrites = new Writes[League] {
    def writes(league: League): JsValue = {
      Json.obj(
        "id" -> league.id,
        "name" -> league.name,
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
        "transferDelayMinutes" -> league.transferDelayMinutes,
        "transferBlockedDuringPeriod" -> league.transferBlockedDuringPeriod,
        "applyPointsAtStartTime" -> league.applyPointsAtStartTime,
        "url" -> {if (league.urlVerified) league.url else "unverifiedurl"},
        "noWildcardForLateRegister" -> league.noWildcardForLateRegister
      )
    }
  }
}
