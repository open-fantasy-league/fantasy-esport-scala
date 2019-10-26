package v1.pickee

import java.sql.Connection

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import models._
import anorm._
import anorm.{Macro, RowParser}
import Macro.ColumnNaming
import play.api.Logger
import utils.NameValueInput

class PickeeExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class PickeeFormInput(id: Long, name: String, value: BigDecimal, active: Boolean, limits: List[String])

case class RepricePickeeFormInput(id: Long, price: BigDecimal)

case class RepricePickeeFormInputList(isInternalId: Boolean, pickees: List[RepricePickeeFormInput])

case class UpdatePickeeFormInput(id: Long, active: Option[Boolean], limitTypes: List[NameValueInput])

case class PickeeLimitsOut(pickee: PickeeRow, limits: Map[String, String])

object PickeeLimitsOut {
  implicit val implicitWrites = new Writes[PickeeLimitsOut] {
    def writes(x: PickeeLimitsOut): JsValue = {
      Json.obj(
        "id" -> x.pickee.externalPickeeId,
        "name" -> x.pickee.pickeeName,
        "price" -> x.pickee.price,
        "active" -> x.pickee.active,
        "limitTypes" -> x.limits,
      )
    }
  }

  val parser: RowParser[PeriodRow] = Macro.namedParser[PeriodRow](ColumnNaming.SnakeCase)
}

trait PickeeRepo{
  def insertPickee(leagueId: Long, pickee: PickeeFormInput)(implicit c: Connection): Long
  def insertPickeeStat(statFieldId: Long, pickeeId: Long)(implicit c: Connection): Long
  def insertPickeeStatDaily(pickeeStatId: Long, period: Option[Int])(implicit c: Connection): Long
  def insertPickeeLimits(
                          pickees: Iterable[PickeeFormInput], newPickeeIds: Seq[Long], limitNamesToIds: Map[String, Long]
                        )(implicit c: Connection): Unit
  def addLimitToPickee(
                        leagueId: Long, pickeeId: Long, limitNames: List[String]
                      )(implicit c: Connection): Unit
  def getAllPickees(leagueId: Long)(implicit c: Connection): Iterable[PickeeRow]
  def getPickees(pickeeIds: List[Long])(implicit c: Connection): Iterable[PickeeRow]
  def getPickeesLimits(leagueId: Long)(implicit c: Connection): Iterable[PickeeLimitsOut]
  def getPickeeLimits(pickeeId: Long)(implicit c: Connection): PickeeLimitsOut
  def getPickeeLimitIds(internalPickeeId: Long)(implicit c: Connection): Iterable[Long]
  def getPickeeStat(
                     leagueId: Long, statFieldId: Option[Long], period: Option[Int]
                   )(implicit c: Connection): Iterable[PickeeStatsOut]
  def getInternalId(leagueId: Long, externalPickeeId: Long)(implicit c: Connection): Option[Long]
  def updatePrice(leagueId: Long, externalPickeeId: Long, price: BigDecimal)(implicit c: Connection): Long
  def updateInactive(leagueId: Long, externalPickeeId: Long, setTo: Boolean)(implicit c: Connection): Long
  def updateLimits(leagueId: Long, externalPickeeId: Long, updatedLimits: List[NameValueInput])(implicit c: Connection)
  def getRandomPickeesFromDifferentFactions(leagueId: Long, packSize: Int)(implicit c: Connection): Iterable[Long]
  def getUserCards(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[CardRow]
  def takenPickeeIds(leagueId: Long, periodVal: Long = 0)(implicit c: Connection): Set[Long]
  def removePickeeFromQueues(pickeeId: Long)(implicit c: Connection)
}

@Singleton
class PickeeRepoImpl @Inject()()(implicit ec: PickeeExecutionContext) extends PickeeRepo{
  private val logger = Logger("application")

  override def insertPickee(leagueId: Long, pickee: PickeeFormInput)(implicit c: Connection): Long = {
    SQL(
      s"""insert into pickee(league_id, pickee_name, external_pickee_id, price, active)
        | values($leagueId, {name}, ${pickee.id}, ${pickee.value}, ${pickee.active}) returning pickee_id""".stripMargin
    ).on("name" -> pickee.name).executeInsert().get
  }

  override def insertPickeeStat(statFieldId: Long, pickeeId: Long)(implicit c: Connection): Long = {
    SQL(
      s"insert into pickee_stat(stat_field_id, pickee_id, previous_rank) values($statFieldId, $pickeeId, 1) returning pickee_stat_id;"
    ).executeInsert().get
  }

  override def insertPickeeStatDaily(pickeeStatId: Long, period: Option[Int])(implicit c: Connection): Long = {
    SQL(
      "insert into pickee_stat_period(pickee_stat_id, period, value) values({pickeeStatId}, {period}, 0.0) returning pickee_stat_period_id;"
    ).on("pickeeStatId" -> pickeeStatId, "period" -> period).executeInsert().get
  }

  override def insertPickeeLimits(
                                   pickees: Iterable[PickeeFormInput], newPickeeIds: Seq[Long], limitNamesToIds: Map[String, Long]
                                 )(implicit c: Connection): Unit = {

    pickees.zipWithIndex.foreach({ case (p, i) => p.limits.foreach({
        // Try except key error
        f => SQL(s"insert into pickee_limit(limit_id, pickee_id) values (${limitNamesToIds(f)}, ${newPickeeIds(i)}) returning pickee_limit_id;").executeInsert().get
      })
    })
  }

  override def addLimitToPickee(
                                   leagueId: Long, pickeeId: Long, limitNames: List[String]
                                 )(implicit c: Connection): Unit = {
    limitNames.foreach(x => {
      SQL"""insert into pickee_limit(limit_id, pickee_id) values (
            (select limit_id from "limit" l join limit_type using(limit_type_id) where league_id = $leagueId AND l.name = $x LIMIT 1),
            $pickeeId
         )
        returning pickee_limit_id
        """.executeInsert().get
    })
  }

  override def getAllPickees(leagueId: Long)(implicit c: Connection): Iterable[PickeeRow] = {
    SQL(s"select pickee_id as internal_pickee_id, external_pickee_id, pickee_name, price, active from pickee where league_id = $leagueId;").as(PickeeRow.parser.*)
  }

  override def getPickees(pickeeIds: List[Long])(implicit c: Connection): Iterable[PickeeRow] = {
    SQL"""select pickee_id as internal_pickee_id, external_pickee_id, pickee_name, price, active from pickee
          where pickee_id = ANY(ARRAY[$pickeeIds])""".as(PickeeRow.parser.*)
  }


  override def getPickeesLimits(leagueId: Long)(implicit c: Connection): Iterable[PickeeLimitsOut] = {
    val rowParser: RowParser[PickeeLimitsRow] = Macro.namedParser[PickeeLimitsRow](ColumnNaming.SnakeCase)
    SQL(
      s"""select pickee_id as internal_pickee_id, external_pickee_id, p.pickee_name, price, active, lt.name as limit_type, l.name as limit_name,
         |coalesce(lt.max, l.max) as "max"
        |from pickee p
        |left join pickee_limit pl using(pickee_id)
        |left join "limit" l using(limit_id)
        |left join limit_type lt using(limit_type_id)
        |where p.league_id = $leagueId;""".stripMargin).as(rowParser.*).groupBy(_.internalPickeeId).map({case(internalPickeeId, v) => {
      PickeeLimitsOut(PickeeRow(
        internalPickeeId, v.head.externalPickeeId, v.head.pickeeName, v.head.price, v.head.active
      ), v.withFilter(_.limitType.isDefined).map(lim => lim.limitType.get -> lim.limitName.get).toMap)
    }})
  }

  override def getPickeeLimits(pickeeId: Long)(implicit c: Connection): PickeeLimitsOut = {
    val rowParser: RowParser[PickeeLimitsRow] = Macro.namedParser[PickeeLimitsRow](ColumnNaming.SnakeCase)
    val rows = SQL(
      s"""select pickee_id as internal_pickee_id, external_pickee_id, p.pickee_name, price, active, lt.name as limit_type, l.name as limit_name,
         | coalesce(lt.max, l.max) as "max"
         |from pickee p
         |left join pickee_limit using(pickee_id)
         |left join "limit" l using(limit_id)
         |left join limit_type lt using(limit_type_id)
         |where pickee_id = $pickeeId;""".stripMargin).as(rowParser.*)
      PickeeLimitsOut(PickeeRow(
        rows.head.internalPickeeId, rows.head.externalPickeeId, rows.head.pickeeName, rows.head.price, rows.head.active
      ), rows.withFilter(_.limitType.isDefined).map(lim => lim.limitType.get -> lim.limitName.get).toMap)
  }

  override def getPickeeLimitIds(internalPickeeId: Long)(implicit c: Connection): Iterable[Long] = {
    SQL("""select limit_id from pickee join pickee_limit using(pickee_id) join "limit" using(limit_id) where pickee_id = {internalPickeeId}""").on(
      "internalPickeeId" -> internalPickeeId
    ).as(SqlParser.long("limit_id").*)
  }

  override def getPickeeStat(
                                  leagueId: Long, statFieldId: Option[Long], period: Option[Int]
                                )(implicit c: Connection): Iterable[PickeeStatsOut] = {
    val rowParser: RowParser[PickeeLimitsAndStatsRow] = Macro.namedParser[PickeeLimitsAndStatsRow](ColumnNaming.SnakeCase)
    //order by p.price desc
    SQL(
      """
        |select pickee_id as internal_pickee_id, external_pickee_id, p.pickee_name, price, active, lt.name as limit_type, l.name as limit_name,
        |coalesce(lt.max, l.max) as "max",
        |sf.name as stat_field_name, psd.value, ps.previous_rank
        |from pickee p join pickee_stat ps using(pickee_id) join pickee_stat_period psd using(pickee_stat_id)
        | join stat_field sf using(stat_field_id)
        | left join limit_type lt on(lt.league_id = p.league_id) left join "limit" l using(limit_type_id)
        | where p.league_id = {leagueId} and ({period} is null or period = {period}) and
        | ({statFieldId} is null or stat_field_id = {statFieldId});
      """.stripMargin).on("leagueId" -> leagueId, "period" -> period, "statFieldId" -> statFieldId).
      as(rowParser.*).groupBy(_.externalPickeeId).map({case(internalPickeeId, v) => {
      PickeeStatsOut(
        PickeeRow(internalPickeeId, v.head.externalPickeeId, v.head.pickeeName, v.head.price, v.head.active),
        v.withFilter(_.limitType.isDefined).map(lim => lim.limitType.get -> lim.limitName.get).toMap,
      v.map(s => s.statFieldName -> s.value).toMap
      )
    }})
  }

  override def getInternalId(leagueId: Long, externalPickeeId: Long)(implicit c: Connection): Option[Long] = {
    SQL"select pickee_id from pickee where league_id = $leagueId and external_pickee_id = $externalPickeeId"
      .as(SqlParser.long("pickee_id").singleOpt)
  }

  // TODO bulk func
  override def updatePrice(leagueId: Long, externalPickeeId: Long, price: BigDecimal)(implicit c: Connection): Long = {
    SQL"update pickee set price = $price where league_id = $leagueId and external_pickee_id = $externalPickeeId".executeUpdate()
  }

  override def updateInactive(leagueId: Long, externalPickeeId: Long, setTo: Boolean)(implicit c: Connection): Long = {
    SQL"update pickee set active = $setTo where league_id = $leagueId and external_pickee_id = $externalPickeeId".executeUpdate()
  }

  override def updateLimits(leagueId: Long, externalPickeeId: Long, updatedLimits: List[NameValueInput])(implicit c: Connection) = {
    updatedLimits.foreach(x => {
      SQL"""
        update pickee_limit pl set limit_id = l2.limit_id
        from "limit" l
        join limit_type lt using(limit_type_id)
        join "limit" l2 on (l2.limit_type_id = lt.limit_type_id AND l2.name = ${x.value})
        where l.limit_id = pl.limit_id AND
         pl.pickee_id = (select pickee_id from pickee where league_id = $leagueId and external_pickee_id = $externalPickeeId limit 1)
         AND lt.name = ${x.name}
        """.executeUpdate()
    })
  }

  override def getRandomPickeesFromDifferentFactions(leagueId: Long, packSize: Int)(implicit c: Connection): Iterable[Long] = {
    // TODO dont need get all rows
    // although this isnt most efficient, its hard work to be efficient, whilst also filtering on league and active
    // and this isnt even that slow for the table size.
    SQL"""select pickee_id from pickee where league_id = $leagueId AND pickee.active
          order by random() limit $packSize
      """.as(SqlParser.long("pickee_id").*)
  }

  override def getUserCards(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[CardRow] = {
    // TODO kind of overlap with team repo
    SQL(
      """
        |select card_id, user_id, pickee_id, colour from card
        |join pickee using(pickee_id) where user_id = {userId} and league_id = {leagueId} and not recycled
      """.stripMargin).on("userId" -> userId, "leagueId" -> leagueId).as(CardRow.parser.*)
  }

  override def takenPickeeIds(leagueId: Long, periodVal: Long=0)(implicit c: Connection): Set[Long] = {
    SQL"""select pickee_id from pickee join card using(pickee_id) where league_id = $leagueId and not recycled
         union select pickee_id from pickee where waiver_cooldown_until_period_end >= periodVal
       """.
      as(SqlParser.long("pickee_id").*).toSet
  }

  override def removePickeeFromQueues(pickeeId: Long)(implicit c: Connection)= {
    // pickeeId is unique. It's unique and not re-used across different leagues
    // so we dont need to filter by leagueId or userId at all
    SQL"""update draft_queue dq set pickee_ids = array_remove(pickee_ids, $pickeeId)""".executeUpdate()
  }
}

