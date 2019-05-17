package v1.team

import java.sql.Connection
import java.time.LocalDateTime
import akka.actor.ActorSystem
import models._
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._

import javax.inject.{Inject, Singleton}

case class TeamOut(externalUserId: Long, username: String, userId: Long, start: Option[LocalDateTime],
                   end: Option[LocalDateTime], isActive: Boolean, pickees: Iterable[CardOut])

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
  def getUserTeam(userId: Long, tstamp: Option[LocalDateTime]=Option.empty[LocalDateTime])(implicit c: Connection): Iterable[CardOut]
  def getUserCards(userId: Long)(implicit c: Connection): Iterable[CardOut]
  def getAllUserTeam(leagueId: Long, tstamp: Option[LocalDateTime]=Option.empty[LocalDateTime])(implicit c: Connection): Iterable[TeamOut]
}

@Singleton
class TeamRepoImpl @Inject()()(implicit ec: TeamExecutionContext) extends TeamRepo{
  override def getUserTeam(userId: Long, tstamp: Option[LocalDateTime]=Option.empty[LocalDateTime])(implicit c: Connection): Iterable[CardOut] = {
    val time = tstamp.getOrElse(LocalDateTime.now())
    println(s"TIIIIIIIIIIME: $time")
    val q =
      """select c.card_id, p.pickee_id as internal_pickee_id, p.external_pickee_id, p.pickee_name, p.price,
          c.colour, sf.stat_field_id, sf.name as stat_field_name, cbm.multiplier,
          l.name as limit_name, lt.name as limit_type_name
           from team t
         join card c using(card_id)
          join pickee p using(pickee_id)
          left join card_bonus_multiplier cbm using(card_id)
          left join stat_field sf using(stat_field_id)
          left join pickee_limit pl using(pickee_id)
          left join "limit" l using(limit_id)
          left join limit_type lt using(limit_type_id)
    where c.user_id = {userId} and timespan @> {time}::timestamptz;
    """
    println(q)
    val rows = SQL(q).on("userId" -> userId, "time" -> time).as(CardWithBonusRowAndLimits.parser.*)
    rows.groupBy(_.cardId).map({case (cardId, v) => {
      val head = v.head
      val limits: Map[String, String] = v.withFilter(_.limitName.isDefined).map(row => row.limitTypeName.get -> row.limitName.get).toMap
      CardOut(
        cardId, head.internalPickeeId, head.externalPickeeId, head.pickeeName, head.price, head.colour,
        v.withFilter(row => row.multiplier.isDefined && row.limitName == head.limitName).map(
          v2 => CardBonusMultiplierRow(v2.statFieldId.get, v2.statFieldName.get, v2.multiplier.get)
        ), limits
      )
    }})
  }

  override def getUserCards(userId: Long)(implicit c: Connection): Iterable[CardOut] = {
    val now = LocalDateTime.now() //TODO can just now in pg
    val q =
      """select c.card_id, p.pickee_id as internal_pickee_id, p.external_pickee_id, p.pickee_name, p.price,
          c.colour, sf.stat_field_id, sf.name as stat_field_name, cbm.multiplier,
          l.name as limit_name, lt.name as limit_type_name from card c
          join pickee p using(pickee_id)
          left join card_bonus_multiplier cbm using(card_id)
          left join stat_field sf using(stat_field_id)
          left join pickee_limit pl using(pickee_id)
          left join "limit" l using(limit_id)
          left join limit_type lt using(limit_type_id)
    where c.user_id = {userId};
    """
    println(q)
    val rows = SQL(q).on("userId" -> userId, "now" -> now).as(CardWithBonusRowAndLimits.parser.*)
    rows.groupBy(_.cardId).map({case (cardId, v) => {
      val head = v.head
      val limits: Map[String, String] = v.withFilter(_.limitName.isDefined).map(row => row.limitTypeName.get -> row.limitName.get).toMap
      CardOut(
        cardId, head.internalPickeeId, head.externalPickeeId, head.pickeeName, head.price, head.colour,
        // if have multiple limits, get the bonus for each row, so need to filter them out of map to not get dupes
        v.withFilter(row => row.multiplier.isDefined && row.limitName == head.limitName).map(
          v2 => CardBonusMultiplierRow(v2.statFieldId.get, v2.statFieldName.get, v2.multiplier.get)
        ), limits
      )
    }})
  }

  override def getAllUserTeam(leagueId: Long, tstamp: Option[LocalDateTime]=Option.empty[LocalDateTime])(implicit c: Connection): Iterable[TeamOut] = {
    val time = tstamp.getOrElse(LocalDateTime.now())
    val q =
      """select c.card_id, u.external_user_id, u.username, user_id, lower(t.timespan) as start, upper(t.timespan) as "end",
        true, p.pickee_id as internal_pickee_id, p.external_pickee_id, c.colour,
        p.pickee_name, p.price as pickee_price, sf.stat_field_id, sf.name as stat_field_name, cbm.multiplier,
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
    where lu.league_id = {leagueId} and timespan @> {time}::timestamptz;
    """
    println(q)
    val out = SQL(q).on("leagueId" -> leagueId, "time" -> time).as(TeamRow.parser.*)
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
              v2 => CardBonusMultiplierRow(v2.statFieldId.get, v2.statFieldName.get, v2.multiplier.get)
            ), limits
          )
        }))
    })
  }
}

