package v1.pickee

import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models.AppDB._
import models._
import play.api.libs.json._

class PickeeExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class PickeeFormInput(id: Long, name: String, value: BigDecimal, active: Boolean, limits: List[String])

case class RepricePickeeFormInput(id: Long, cost: BigDecimal)

case class RepricePickeeFormInputList(isInternalId: Boolean, pickees: List[RepricePickeeFormInput])

case class PickeeQuery(pickee: Pickee, limitType: Option[LimitType], limit: Option[Limit])

case class PickeeOut(pickee: Pickee, limits: Map[String, String])

case class PickeeStatsOutput(
                              externalId: Long, name: String, stats: Map[String, Double], limits: Map[String, String],
                              cost: BigDecimal
                            )

object PickeeOut{
  implicit val implicitWrites = new Writes[PickeeOut] {
    def writes(p: PickeeOut): JsValue = {
      Json.obj(
        "pickee" -> p.pickee,
        "limits" -> p.limits
      )
    }
  }
}

object PickeeStatsOutput{
  implicit val implicitWrites = new Writes[PickeeStatsOutput] {
    def writes(p: PickeeStatsOutput): JsValue = {
      Json.obj(
        "id" -> p.externalId,
        "name" -> p.name,
        "stats" -> p.stats,
        "limits" -> p.limits,
        "cost" -> p.cost
      )
    }
  }
}

case class GetPickeesOutput(pickees: List[PickeeStatsOutput])
case class PickeeStatQuery(query: Iterable[(models.Pickee, models.PickeeStat, models.PickeeStatDaily, models.LeagueStatField)])


trait PickeeRepo{
  def insertPickee(leagueId: Long, pickee: PickeeFormInput): Pickee
  def insertPickeeStat(statFieldId: Long, pickeeId: Long): PickeeStat
  def insertPickeeStatDaily(pickeeStatId: Long, period: Option[Int]): PickeeStatDaily
  def getPickeeStats(leagueId: Long, period: Option[Int]): Iterable[PickeeStatsOutput]
  def getPickees(leagueId: Long): Iterable[Pickee]
  def getPickeesWithLimits(leagueId: Long): Iterable[PickeeOut]
  def getPickeeStat(leagueId: Long, statFieldId: Long, period: Option[Int]): Iterable[(PickeeStat, PickeeStatDaily)]
  def pickeeQueryExtractor(query: Iterable[PickeeQuery]): Iterable[PickeeOut]
}

@Singleton
class PickeeRepoImpl @Inject()()(implicit ec: PickeeExecutionContext) extends PickeeRepo{

  override def insertPickee(leagueId: Long, pickee: PickeeFormInput): Pickee = {
    pickeeTable.insert(new Pickee(
      leagueId,
      pickee.name,
      pickee.id, // in the case of dota we have the pickee id which is unique for AM in league 1
      // and AM in league 2. however we still want a field which is always AM hero id
      pickee.value,
      pickee.active,
    ))
  }

  override def insertPickeeStat(statFieldId: Long, pickeeId: Long): PickeeStat = {
    pickeeStatTable.insert(new PickeeStat(
      statFieldId, pickeeId
    ))
  }

  override def insertPickeeStatDaily(pickeeStatId: Long, period: Option[Int]): PickeeStatDaily = {
    pickeeStatDailyTable.insert(new PickeeStatDaily(
      pickeeStatId, period
    ))
  }

  override def getPickees(leagueId: Long): Iterable[Pickee] = {
   from(pickeeTable, leagueTable)(
     (p, l) => where(p.leagueId === l.id)
       select(p)
   )
 }


  override def getPickeesWithLimits(leagueId: Long): Iterable[PickeeOut] = {
  val query = join(leagueTable, pickeeTable, pickeeLimitTable.leftOuter, limitTable.leftOuter, limitTypeTable.leftOuter)(
    (l, p, pf, f, ft) =>
      select((p, ft, f))
        on(
        p.leagueId === l.id, pf.map(_.pickeeId) === p.id, pf.map(_.limitId) === f.map(_.id),
        f.map(_.limitTypeId) === ft.map(_.id)
      )
  )
  pickeeQueryExtractor(query.map(x => PickeeQuery(x._1, x._2, x._3)))
  }

  override def getPickeeStats(
                                  leagueId: Long, period: Option[Int]
                                ): Iterable[PickeeStatsOutput] = {
    val query = //: Iterable[(Pickee, PickeeStat, PickeeStatDaily, LeagueStatField, Option[LimitType], Option[Limit])] =
      join(
      pickeeTable, pickeeStatTable, pickeeStatDailyTable, leagueStatFieldTable, pickeeLimitTable.leftOuter, limitTable.leftOuter, limitTypeTable.leftOuter
    )((p, ps, s, lsf, pf, f, ft) =>
      where(
          p.leagueId === leagueId and s.period === period
      )
        select (p, ps, s, lsf, ft, f)
        orderBy (p.name)
        on (ps.pickeeId === p.id, s.pickeeStatId === ps.id, ps.statFieldId === lsf.id, 
          pf.map(_.pickeeId) === p.id, pf.map(_.limitId) === f.map(_.id), f.map(_.limitTypeId) === ft.map(_.id)
          )
    )
    //v.map(x => x.              limitType.name -> x.limit.name).toMap
    // TODO hmm have to resort after groupby
    val groupByPickee = query.groupBy(_._1)
    val out: Iterable[PickeeStatsOutput] = groupByPickee.map({case (p, v) => {
      val stats = v.groupBy(_._4).mapValues(_.head._3).map(x => x._1.name -> x._2.value)
      val limits = v.filter(x => x._5.isDefined).map(x => x._5.get.name -> x._6.get.name).toMap
      PickeeStatsOutput(p.externalId, p.name, stats, limits, p.cost)
  }}).toSeq.sortBy(_.name)
  out
}

  override def getPickeeStat(
                                  leagueId: Long, statFieldId: Long, period: Option[Int]
                                ): Iterable[(PickeeStat, PickeeStatDaily)] = {
    from(
      pickeeTable, pickeeStatTable, pickeeStatDailyTable
    )((p, ps, s) =>
      where(
        ps.pickeeId === p.id and s.pickeeStatId === ps.id and
          p.leagueId === leagueId and ps.statFieldId === statFieldId and s.period === period
      )
        select (ps, s)
        orderBy (s.value desc)
    )
  }
  override def pickeeQueryExtractor(query: Iterable[PickeeQuery]): Iterable[PickeeOut] = {
    query.groupBy(_.pickee).map({case (p, v) => {
      val limits: Map[String, String] = (
          for (x <- v if x.limitType.isDefined) yield x.limitType.get.name -> x.limit.get.name
        ).toMap
      PickeeOut(p, limits)
    }})
  }
}

