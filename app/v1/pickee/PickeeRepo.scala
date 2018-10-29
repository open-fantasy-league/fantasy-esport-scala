package v1.pickee

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models._
import utils.CostConverter

import scala.collection.mutable.ArrayBuffer

class PickeeExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class PickeeFormInput(id: Int, name: String, value: Double, active: Boolean, faction: Option[String])

trait PickeeRepo{
  def insertPickee(leagueId: Int, pickee: PickeeFormInput): Pickee
  def insertPickeeStat(statFieldId: Long, pickeeId: Long): PickeeStat
  def insertPickeeStatDaily(pickeeStatId: Long, day: Option[Int]): PickeeStatDaily
}

@Singleton
class PickeeRepoImpl @Inject()()(implicit ec: PickeeExecutionContext) extends PickeeRepo{

  override def insertPickee(leagueId: Int, pickee: PickeeFormInput): Pickee = {
    AppDB.pickeeTable.insert(new Pickee(
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
    AppDB.pickeeStatTable.insert(new PickeeStat(
      statFieldId, pickeeId
    ))
  }

  override def insertPickeeStatDaily(pickeeStatId: Long, day: Option[Int]): PickeeStatDaily = {
    AppDB.pickeeStatDailyTable.insert(new PickeeStatDaily(
      pickeeStatId, day
    ))
  }
}

