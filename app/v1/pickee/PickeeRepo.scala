package v1.pickee

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models.AppDB._
import models.{Pickee, PickeeStat, PickeeStatDaily, LeagueStatField}
import utils.CostConverter
import play.api.libs.json._

import scala.collection.mutable.ArrayBuffer

class PickeeExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class PickeeFormInput(id: Int, name: String, value: Double, active: Boolean, faction: Option[String])

case class PickeeStatOutput(statField: String, value: Double)
case class PickeeOutput(externalId: Int, name: String, stats: List[PickeeStatOutput])

object PickeeStatOutput{
  implicit val implicitWrites = new Writes[PickeeStatOutput] {
    def writes(s: PickeeStatOutput): JsValue = {
      Json.obj(
        "name" -> s.statField,
        "value" -> s.value
      )
    }
  }
}

object PickeeOutput{
  implicit val implicitWrites = new Writes[PickeeOutput] {
    def writes(p: PickeeOutput): JsValue = {
      Json.obj(
        "externalId" -> p.externalId,
        "name" -> p.name,
        "stats" -> p.stats
      )
    }
  }
}

case class GetPickeesOutput(pickees: List[PickeeOutput])
case class PickeeStatQuery(query: Iterable[(models.Pickee, models.PickeeStat, models.PickeeStatDaily, models.LeagueStatField)])


trait PickeeRepo{
  def insertPickee(leagueId: Int, pickee: PickeeFormInput): Pickee
  def insertPickeeStat(statFieldId: Long, pickeeId: Long): PickeeStat
  def insertPickeeStatDaily(pickeeStatId: Long, day: Option[Int]): PickeeStatDaily
  def getPickeeStats(leagueId: Int, day: Option[Int]): List[PickeeOutput]
  //def getPickees(leagueId: Int): Iterable[Pickee]
}

@Singleton
class PickeeRepoImpl @Inject()()(implicit ec: PickeeExecutionContext) extends PickeeRepo{

  override def insertPickee(leagueId: Int, pickee: PickeeFormInput): Pickee = {
    pickeeTable.insert(new Pickee(
      leagueId,
      pickee.name,
      pickee.id, // in the case of dota we have the pickee id which is unique for AM in league 1
      // and AM in league 2. however we still want a field which is always AM hero id
      pickee.faction,
      CostConverter.unconvertCost(pickee.value),
      pickee.active
    ))
  }

  override def insertPickeeStat(statFieldId: Long, pickeeId: Long): PickeeStat = {
    pickeeStatTable.insert(new PickeeStat(
      statFieldId, pickeeId
    ))
  }

  override def insertPickeeStatDaily(pickeeStatId: Long, day: Option[Int]): PickeeStatDaily = {
    pickeeStatDailyTable.insert(new PickeeStatDaily(
      pickeeStatId, day
    ))
  }

  override def getPickeeStats(
                                  leagueId: Int, day: Option[Int]
                                ): List[PickeeOutput] = {
    val query: Iterable[(Pickee, PickeeStat, PickeeStatDaily, LeagueStatField)] = from(
      pickeeTable, pickeeStatTable, pickeeStatDailyTable, leagueStatFieldTable
    )((p, ps, s, lsf) =>
      where(
        ps.pickeeId === p.id and s.pickeeStatId === ps.id and
          p.leagueId === leagueId and ps.statFieldId === lsf.id and s.day === day
      )
        select (p, ps, s, lsf)
        orderBy (lsf.name, s.value desc)
    )
    // inputs.pickees.map(p => p.id -> p).toMap
    //.groupBy(_._1).mapValues(_.groupBy(_._2).mapValues(_.map(_._3)))
    val grouped = query.groupBy(_._1).mapValues(_.groupBy(_._4).mapValues(_.head._3))
    grouped.map({case (k, v) =>
      PickeeOutput(k.externalId, k.name, v.map({case (k2, v2) => PickeeStatOutput(k2.name, v2.value)}).toList)}).toList
  }

  //override def getPickees(leagueId: Int): Iterable[Pickee]
}

