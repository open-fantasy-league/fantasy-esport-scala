package v1.result

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models._
import scala.collection.mutable.ArrayBuffer

class ResultExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait ResultRepo{
  def show(id: Long): Option[Resultu]
//  def insertLeague(formInput: LeagueFormInput): League
//  def getStatFields(league: League): Array[String]
//  def insertLeagueStatField(leagueId: Int, name: String): LeagueStatFields
//  def insertPickee(leagueId: Int, pickee: PickeeFormInput): Pickee
//  def insertPickeeStat(statFieldId: Long, pickeeId: Long): PickeeStat
//  def insertPickeeStatDaily(pickeeStatId: Long, day: Int): PickeeStatDaily
//  def insertPickeeStatOverall(pickeeStatId: Long): PickeeStatOverall
//  def insertLeagueUser(league: League, userId: Int): LeagueUser
//  def insertLeagueUserStat(statFieldId: Long, leagueUserId: Long): LeagueUserStat
//  def insertLeagueUserStatDaily(leagueUserStatId: Long, day: Int): LeagueUserStatDaily
//  def insertLeagueUserStatOverall(leagueUserStatId: Long): LeagueUserStatOverall
  //def update()
}

@Singleton
class ResultRepoImpl @Inject()()(implicit ec: ResultExecutionContext) extends ResultRepo{
  override def show(id: Long): Option[Resultu] = {
    AppDB.resultTable.lookup(id)
  }


}

