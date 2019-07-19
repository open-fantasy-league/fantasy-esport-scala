package v1.team

import java.sql.Connection

import akka.actor.ActorSystem
import models._
import anorm._
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import javax.inject.{Inject, Singleton}
import play.api.Logger
import v1.league.LeagueRepo

case class TeamOut(externalUserId: Long, username: String, userId: Long, start: Option[Int],
                   end: Option[Int], isActive: Boolean, pickees: Iterable[CardOut])

object TeamOut {
  implicit val implicitWrites = new Writes[TeamOut] {
    def writes(x: TeamOut): JsValue = {
      Json.obj(
        "userId" -> x.externalUserId,
        "username" -> x.username,
        "userId" -> x.userId,
        "start" -> x.start,
        "end" -> x.end,
        "isActive" -> x.isActive,
        "team" -> x.pickees
      )
    }
  }
}

class TeamExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TeamRepo{
  def getUserTeam(userId: Long, period: Option[Int]=Option.empty[Int])(implicit c: Connection): Iterable[CardOut]
  def getUserCards(userId: Long, showLastXPeriodStats: Option[Int], currentPeriodId: Option[Long], showOverallStats:Boolean)
                  (implicit c: Connection): Iterable[CardOut]
  def getAllUserTeam(leagueId: Long, period: Option[Int]=Option.empty[Int])(implicit c: Connection): Iterable[TeamOut]
  def cardInTeam(cardId: Long, currentPeriod: Option[Int])(implicit c: Connection): Boolean
  def getUserTeamForPeriod(
                            userId: Long, startPeriod: Int,
                            endPeriod: Option[Int]
                          )(implicit c: Connection): Iterable[CardOut]
}

@Singleton
class TeamRepoImpl @Inject()()(implicit ec: TeamExecutionContext, leagueRepo: LeagueRepo) extends TeamRepo{
  private val logger = Logger("application")
  override def getUserTeam(userId: Long, period: Option[Int]=Option.empty[Int])(implicit c: Connection): Iterable[CardOut] = {
    println(s"TIIIIIIIIIIME: $period")
    val q =
      """select c.card_id, p.pickee_id as internal_pickee_id, p.external_pickee_id, p.pickee_name, p.price,
          c.colour, sf.stat_field_id, sf.name as stat_field_name, sf.description as stat_field_description, cbm.multiplier,
          l.name as limit_name, lt.name as limit_type_name
           from team t
         join card c using(card_id)
          join pickee p using(pickee_id)
          left join card_bonus_multiplier cbm using(card_id)
          left join stat_field sf using(stat_field_id)
          left join pickee_limit pl using(pickee_id)
          left join "limit" l using(limit_id)
          left join limit_type lt using(limit_type_id)
    where c.user_id = {userId} and (({period} is null and upper(timespan) is null) OR timespan @> {period});
    """
    println(q)
    val rows = SQL(q).on("userId" -> userId, "period" -> period).as(CardWithBonusRowAndLimits.parser.*)
    rows.groupBy(_.cardId).map({case (cardId, v) => {
      val head = v.head
      val limits: Map[String, String] = v.withFilter(_.limitName.isDefined).map(row => row.limitTypeName.get -> row.limitName.get).toMap
      CardOut(
        cardId, head.internalPickeeId, head.externalPickeeId, head.pickeeName, head.price, head.colour,
        v.withFilter(row => row.multiplier.isDefined && row.limitName == head.limitName).map(
          v2 => CardBonusMultiplierRow(v2.statFieldId.get, v2.statFieldName.get, v2.multiplier.get, v2.statFieldDescription)
        ), limits
      )
    }})
  }

  override def getUserTeamForPeriod(
                             userId: Long, startPeriod: Int,
                             endPeriod: Option[Int]
                           )(implicit c: Connection): Iterable[CardOut] = {
    //todo check this efficient
    // TODO transfer delay for in-period new users
    val q =
      """select c.card_id, p.pickee_id as internal_pickee_id, p.external_pickee_id, p.pickee_name, p.price,
          c.colour, sf.stat_field_id, sf.name as stat_field_name, sf.description as stat_field_description, cbm.multiplier,
          l.name as limit_name, lt.name as limit_type_name
           from team t
         join card c using(card_id)
          join pickee p using(pickee_id)
          left join card_bonus_multiplier cbm using(card_id)
          left join stat_field sf using(stat_field_id)
          left join pickee_limit pl using(pickee_id)
          left join "limit" l using(limit_id)
          left join limit_type lt using(limit_type_id)
    where c.user_id = {userId} and timespan @> int4range({startPeriod}, {endPeriod});
    """
    logger.info(f"getUserTeamForPeriod: userId $userId, startPeriod $startPeriod, endPeriod $endPeriod")
    println(q)
    val rows = SQL(q).on("userId" -> userId, "startPeriod" -> startPeriod, "endPeriod" -> endPeriod).as(CardWithBonusRowAndLimits.parser.*)
    logger.info(f"getUserTeamForPeriod rows (pickeename, cardid): ${rows.map(x => (x.pickeeName, x.cardId)).mkString(",")}")
    rows.groupBy(_.cardId).map({case (cardId, v) => {
      val head = v.head
      val limits: Map[String, String] = v.withFilter(_.limitName.isDefined).map(row => row.limitTypeName.get -> row.limitName.get).toMap
      CardOut(
        cardId, head.internalPickeeId, head.externalPickeeId, head.pickeeName, head.price, head.colour,
        v.withFilter(row => row.multiplier.isDefined && row.limitName == head.limitName).map(
          v2 => CardBonusMultiplierRow(v2.statFieldId.get, v2.statFieldName.get, v2.multiplier.get, v2.statFieldDescription)
        ), limits
      )
    }})
  }

  override def getUserCards(userId: Long, showLastXPeriodStats: Option[Int], currentPeriodId: Option[Long], showOverallStats: Boolean)
                           (implicit c: Connection): Iterable[CardOut] = {
    val currentPeriodValue = currentPeriodId.flatMap(x => leagueRepo.getPeriod(x).map(_.value))
    var extraSelects = ""
    var extraJoins = ""
    // need this variable as although they asked for the stats, if league hasnt started, cant give them any
    var showingRecentPeriodStats = false
    if (showLastXPeriodStats.isEmpty || currentPeriodValue.isEmpty){
      if (showOverallStats) {
        extraSelects += ",sf2.name as stat_field_name2, null as period, psp.value as value"
        extraJoins += """
                    left join pickee_stat ps using(pickee_id)
                    left join pickee_stat_period psp
                    on (ps.pickee_stat_id = psp.pickee_stat_id AND psp.period IS NULL)
                    left join stat_field sf2 on (ps.stat_field_id = sf2.stat_field_id)
                    """
      }
      else{
        extraSelects += ", null as stat_field_name2, null as period, null as value"
      }
    }
    else{
      showingRecentPeriodStats = true
      extraSelects += ",sf2.name as stat_field_name2, psp.period, psp.value as value"
      extraJoins += """
                    left join pickee_stat ps using(pickee_id)
                    left join pickee_stat_period psp
                    """
      if (showOverallStats){
        extraJoins += """ on (ps.pickee_stat_id = psp.pickee_stat_id AND (psp.period IS NULL OR psp.period IN ({periodValues})))"""
      }
      else{
        extraJoins += " on (ps.pickee_stat_id = psp.pickee_stat_id AND psp.period IN ({periodValues}))"
      }
      extraJoins += " left join stat_field sf2 on (ps.stat_field_id = sf2.stat_field_id)"
    }
    val periodValues = ((currentPeriodValue.getOrElse(0) - showLastXPeriodStats.getOrElse(0)) to currentPeriodValue.getOrElse(0)).
      filter(_ > 0).toList
    logger.info(s"showLastXPeriodStats: ${showLastXPeriodStats.mkString("")}")
    logger.warn(s"currentPeriodValue: ${currentPeriodValue.mkString("")}")
    val rows =
      SQL(s"""select c.card_id, p.pickee_id as internal_pickee_id, p.external_pickee_id, p.pickee_name, p.price,
        c.colour, sf.stat_field_id, sf.name as stat_field_name, sf.description as stat_field_description, cbm.multiplier,
        l.name as limit_name, lt.name as limit_type_name
         $extraSelects
         from card c
        join pickee p using(pickee_id)
        left join card_bonus_multiplier cbm using(card_id)
        left join stat_field sf using(stat_field_id)
        left join pickee_limit pl using(pickee_id)
        left join "limit" l using(limit_id)
        left join limit_type lt using(limit_type_id)
        $extraJoins
  where c.user_id = $userId and not c.recycled;
  """).on("periodValues" -> periodValues).as(CardWithBonusRowAndLimitsAndStats.parser.*)
    rows.groupBy(_.cardId).map({
      case (cardId, v) => {
        val head = v.head
        val limits: Map[String, String] = v.filter(_.limitName.isDefined).groupBy(_.limitTypeName).
          map({case (limitTypeName, rows) => {
            val row = rows.head
            limitTypeName.get -> row.limitName.get
          }})
        // TODO hacky get or else. do i need fix?
        val recentPeriodStats: Map[Int, Map[String, Double]] = if (showOverallStats || showingRecentPeriodStats) {
          v.filter(_.period.isDefined).groupBy(_.period.get).
            mapValues(rows => rows.map(row => row.statFieldName2.get -> row.value.get).toMap)
        } else Map()
        // TODO is this most efficient way?
        val overallStats: Map[String, Double] = recentPeriodStats.getOrElse(0, Map())
        val bonuses = v.filter(_.statFieldId.isDefined).
          groupBy(row => (row.statFieldId.get, row.statFieldName.get, row.multiplier.get, row.statFieldDescription)).keys.map(t => {
          CardBonusMultiplierRow(t._1, t._2, t._3, t._4)
        })
        CardOut(
          cardId, head.internalPickeeId, head.externalPickeeId, head.pickeeName, head.price, head.colour,
          bonuses, limits, overallStats, if (showingRecentPeriodStats) recentPeriodStats else Map()
        )
      }
    })
  }

  override def cardInTeam(cardId: Long, currentPeriod: Option[Int])(implicit c: Connection): Boolean = {
    SQL"""select 1 as yeppers from team where card_id = $cardId AND ($currentPeriod IS NULL OR lower(timespan) <= $currentPeriod)""".
      as(SqlParser.int("yeppers").singleOpt).isDefined
  }

  override def getAllUserTeam(leagueId: Long, period: Option[Int]=Option.empty[Int])(implicit c: Connection): Iterable[TeamOut] = {
    val q =
      """select c.card_id, u.external_user_id, u.username, user_id, lower(t.timespan) as start, upper(t.timespan) as "end",
        true, p.pickee_id as internal_pickee_id, p.external_pickee_id, c.colour,
        p.pickee_name, p.price as pickee_price, sf.stat_field_id, sf.name as stat_field_name, sf.description as stat_field_description,
        cbm.multiplier,
        l.name as limit_name, lt.name as limit_type_name
        from team t
        join card c using(card_id)
        left join card_bonus_multiplier cbm using (c.card_id)
        left join stat_field sf using (stat_field_id)
                    join pickee p using(pickee_id)
                    join useru u using(user_id)
                              left join pickee_limit pl using(pickee_id)
           left join "limit" l using(limit_id)
           left join limit_type lt using(limit_type_id)
    where lu.league_id = {leagueId} and timespan @> {period};
    """
    println(q)
    val out = SQL(q).on("leagueId" -> leagueId, "period" -> period).as(TeamRow.parser.*)
    println(out.mkString(","))
    teamRowsToOut(out)
  }

  private def teamRowsToOut(teamRows: Iterable[TeamRow]): Iterable[TeamOut] = {
    teamRows.groupBy(_.userId).map({ case (userId, v) =>
      val head = v.head
      TeamOut(head.externalUserId, head.username, userId, head.start,
        head.end, head.isActive, v.groupBy(_.cardId).map({ case (cardId, rows) =>
          val head2 = rows.head
          val limits: Map[String, String] = rows.withFilter(_.limitName.isDefined).map(row => row.limitTypeName.get -> row.limitName.get).toMap
          CardOut(
            cardId, head2.internalPickeeId, head2.externalPickeeId, head2.pickeeName, head2.pickeePrice, head2.colour,
            // if have multiple limits, get the bonus for each row, so need to filter them out of map to not get dupes
            rows.withFilter(row => row.multiplier.isDefined && row.limitName == head2.limitName).map(
              v2 => CardBonusMultiplierRow(v2.statFieldId.get, v2.statFieldName.get, v2.multiplier.get, v2.statFieldDescription)
            ), limits
          )
        }))
    })
  }
}

