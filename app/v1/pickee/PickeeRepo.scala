package v1.pickee

import java.sql.Connection
import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models._
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

class PickeeExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class PickeeFormInput(id: Long, name: String, value: BigDecimal, active: Boolean, limits: List[String])

case class RepricePickeeFormInput(id: Long, cost: BigDecimal)

case class RepricePickeeFormInputList(isInternalId: Boolean, pickees: List[RepricePickeeFormInput])

case class PickeeLimits(pickee: PickeeRow, limits: Map[String, String])

trait PickeeRepo{
  def insertPickee(leagueId: Long, pickee: PickeeFormInput)(implicit c: Connection): Long
  def insertPickeeStat(statFieldId: Long, pickeeId: Long)(implicit c: Connection): Long
  def insertPickeeStatDaily(pickeeStatId: Long, period: Option[Int])(implicit c: Connection): Long
  def insertPickeeLimits(
                          pickees: Iterable[PickeeFormInput], newPickeeIds: Seq[Long], limitNamesToIds: Map[String, Long]
                        )(implicit c: Connection): Unit
  def getPickees(leagueId: Long)(implicit c: Connection): Iterable[PickeeRow]
  def getPickeesLimits(leagueId: Long)(implicit c: Connection): Iterable[PickeeLimits]
  def getPickeeStat(
                     leagueId: Long, statFieldId: Option[Long], period: Option[Int]
                   )(implicit c: Connection): Iterable[PickeeStatsOut]
  def getInternalId(leagueId: Long, externalPickeeId: Long)(implicit c: Connection): Option[Long]
  def updateCost(pickeeId: Long, cost: BigDecimal)(implicit c: Connection): Long
}

@Singleton
class PickeeRepoImpl @Inject()()(implicit ec: PickeeExecutionContext) extends PickeeRepo{

  override def insertPickee(leagueId: Long, pickee: PickeeFormInput)(implicit c: Connection): Long = {
    SQL(
      """insert into pickee(league_id, pickee_name, external_pickee_id, value, active)
        | values($leagueId, ${pickee.name}, ${pickee.id}, ${pickee.value}, ${pickee.active})""".stripMargin
    ).executeInsert()
  }

  override def insertPickeeStat(statFieldId: Long, pickeeId: Long)(implicit c: Connection): Long = {
    SQL(
      "insert into pickee_stat(stat_field_id, pickee_id) values($statFieldId, $pickeeId);"
    ).executeInsert()
  }

  override def insertPickeeStatDaily(pickeeStatId: Long, period: Option[Int])(implicit c: Connection): Long = {
    SQL(
      "insert into pickee_stat_daily(pickee_stat_id, period) values($pickeeStatId, $period);"
    ).executeInsert()
  }

  override def insertPickeeLimits(
                                   pickees: Iterable[PickeeFormInput], newPickeeIds: Seq[Long], limitNamesToIds: Map[String, Long]
                                 )(implicit c: Connection): Unit = {

    pickees.zipWithIndex.foreach({ case (p, i) => p.limits.foreach({
        // Try except key error
        f => SQL("insert into pickee_limit(limit_id, pickee_id) values ({}, {});").onParams(limitNamesToIds(f), newPickeeIds(i)).executeInsert()
      })
    })
  }

  override def getPickees(leagueId: Long)(implicit c: Connection): Iterable[PickeeRow] = {
    SQL("select pickee_id, name, cost from pickee where league_id = $leagueId;").as(PickeeRow.parser.*)
 }


  override def getPickeesLimits(leagueId: Long)(implicit c: Connection): Iterable[PickeeLimits] = {
    val rowParser: RowParser[PickeeLimitsRow] = Macro.namedParser[PickeeLimitsRow](ColumnNaming.SnakeCase)
    SQL(
      """select pickee_id, p.pickee_name, cost, lt.name as limit_type, l.name as limit_name, coalesce(lt.max, l.max)
        |from pickee p
        |left join limit_type lt using(league_id)
        |left join "limit" l using(limit_type_id)
        |where league_id = $leagueId;""".stripMargin).as(rowParser.*).groupBy(_.externalPickeeId).map({case(pickeeId, v) => {
      PickeeLimits(PickeeRow(pickeeId, v.head.pickeeName, v.head.cost), v.map(lim => lim.limitType -> lim.limitName).toMap)
    }})
  }

  override def getPickeeStat(
                                  leagueId: Long, statFieldId: Option[Long], period: Option[Int]
                                )(implicit c: Connection): Iterable[PickeeStatsOut] = {
    val rowParser: RowParser[PickeeLimitsAndStatsRow] = Macro.namedParser[PickeeLimitsAndStatsRow](ColumnNaming.SnakeCase)
    SQL(
      """
        |select pickee_id, p.pickee_name, cost, lt.name as limit_type, l.name as limit_name, coalesce(lt.max, l.max),
        |sf.name as stat_field_name, psd.value, ps.previous_rank
        |from pickee p join pickee_stat ps using(pickee_id) join pickee_stat_daily psd using(pickee_stat_id)
        | join stat_field sf using(stat_field_id)
        | left join limit_type lt using(league_id) left join "limit" l using(limit_type_id)
        | where league_id = $leagueId and ($period is null or period = $period) and
        | ($statFieldId is null or stat_field_id = $statFieldId)
        | order by p.cost desc
      """.stripMargin).as(rowParser.*).groupBy(_.externalPickeeId).map({case(pickeeId, v) => {
      PickeeStatsOut(
        PickeeRow(pickeeId, v.head.pickeeName, v.head.cost), v.map(lim => lim.limitType -> lim.limitName).toMap,
      v.map(s => s.statFieldName -> s.value).toMap
      )
    }})
  }

  override def getInternalId(leagueId: Long, externalPickeeId: Long)(implicit c: Connection): Option[Long] = {
    SQL(
      "select pickee_id from pickee where league_id = $leagueId and external_pickee_id = $externalPickeeId;"
    ).as(SqlParser.long("pickee_id").singleOpt)
  }

  // TODO bulk func
  override def updateCost(pickeeId: Long, cost: BigDecimal)(implicit c: Connection): Long = {
    SQL("update pickee set cost = $cost where pickee_id = $pickeeId").executeUpdate()
  }
}

