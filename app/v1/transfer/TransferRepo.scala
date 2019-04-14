package v1.transfer

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import anorm._
import play.api.db._
import models._
import v1.league.LeagueRepo


class TransferExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TransferRepo{
  def getLeagueUserTransfer(leagueUserId: Long, processed: Option[Boolean])(implicit c: Connection): Iterable[TransferRow]
  def processLeagueUserTransfer(leagueUserId: Long)(implicit c: Connection): Unit
  def changeTeam(leagueUserId: Long, toBuyIds: Set[Long], toSellIds: Set[Long],
                 oldTeamIds: Set[Long], time: LocalDateTime
                )(implicit c: Connection): Unit
  def pickeeLimitsValid(leagueId: Long, newTeamIds: Set[Long])(implicit c: Connection): Boolean
  def insert(
              leagueUserId: Long, pickeeId: Long, currentTime: LocalDateTime,
              scheduledUpdateTime: Option[LocalDateTime], cost: BigDecimal, applyWildcard: Boolean
            )(implicit c: Connection)
  def setProcessed(transferId: Long)(implicit c: Connection): Long
}

@Singleton
class TransferRepoImpl @Inject()()(implicit ec: TransferExecutionContext, leagueRepo: LeagueRepo) extends TransferRepo{
  override def getLeagueUserTransfer(leagueUserId: Long, processed: Option[Boolean])(implicit c: Connection): Iterable[TransferRow] = {
    val processedFilter = if (unprocessed.isEmpty) "" else s"and processed = $processed"
    transferId, leagueUserId, internalPickeeId, externalPickeeId, pickeeName, isBuy, timeMade, scheduledFor, processed, cost, wasWildcard
    SQL(
      """
        |select transfer_id, league_user_id, p.pickee_id, p.external_pickee_id,
        | p.pickee_name, isBuy,
        | timeMade, scheduledFor, processed, cost, wasWildcard
        | from transfer join pickee p using(pickee_id) where league_user_id = {} {};
      """.stripMargin).onParams(leagueUserId, processedFilter).as(TransferRow.parser.*)
  }
  // ALTER TABLE team ALTER COLUMN id SET DEFAULT nextval('team_seq');
  override def changeTeam(leagueUserId: Long, toBuyIds: Set[Long], toSellIds: Set[Long],
                           oldTeamIds: Set[Long], time: LocalDateTime
                         )(implicit c: Connection) = {
      val newPickees: Set[Long] = (oldTeamIds -- toSellIds) ++ toBuyIds
      val q =
        """update team t set timespan = tstzrange(lower(timespan), now())
    where t.league_user_id = {leagueUserId} and upper(t.timespan) is NULL;
    """
      SQL(q).on("leagueUserId" -> leagueUserId).executeUpdate()
    println("Ended current team")
    SQL("update league_user set change_tstamp = null where league_user_id = {leagueUserId};").on("leagueUserId" -> leagueUserId).executeUpdate()
    print(newPickees.mkString(", "))
    newPickees.map(t => {
      SQL("insert into team(league_user_id, pickee_id, timespan) values({}, {}, tstzrange({now}, null));").
        onParams(leagueUserId, t, time).executeInsert()
    })
    println("Inserted new team")
  }

  override def processLeagueUserTransfer(leagueUserId: Long)(implicit c: Connection)  = {
    val now = LocalDateTime.now()
    // TODO need to lock here?
    // TODO map and filter together
    val transfers = getLeagueUserTransfer(leagueUserId, Some(false))
    // TODO single iteration
    val toSellIds = transfers.filter(!_.isBuy).map(_.pickeeId).toSet
    val toBuyIds = transfers.filter(_.isBuy).map(_.pickeeId).toSet
      val q =
        """select pickee_id from team t where t.league_user_id = {leagueUserId} and upper(t.timespan) is NULL;
              """
      val oldTeamIds = SQL(q).on("leagueUserId" -> leagueUserId).as(SqlParser.scalar[Long] *).toSet
      changeTeam(leagueUserId, toBuyIds, toSellIds, oldTeamIds, now)
      transfers.map(t => setProcessed(t.transferId))
  }

  override def pickeeLimitsValid(leagueId: Long, newTeamIds: Set[Long])(implicit c: Connection): Boolean = {
    // TODO need to check this againbst something. doesnt work right now
    val q =
      """select not exists (select 1 from pickee p
        | join limit_type lt using(league_id)
        | join "limit" l using(limit_type_id)
        | where p.league_id = {leagueId} and p.pickee_id in {newTeamIds} group by (lt.max, l.limit_id) having count(*) > lt.max);
      """
    SQL(q).on("leagueId" -> leagueId, "newTeamIds" -> newTeamIds).as(SqlParser.scalar[Boolean].single)
  }

  override def insert(
                       leagueUserId: Long, pickeeId: Long, currentTime: LocalDateTime,
                       scheduledUpdateTime: Option[LocalDateTime], cost: BigDecimal, applyWildcard: Boolean
                     )(implicit c: Connection): Long = {
    SQL(
      """
        |insert into transfer(league_user_id, pickee_id, is_buy, time_made, scheduled_for, processed, cost, was_wildcard)
        |""".stripMargin
    ).onParams(leagueUser.leagueUserId, p.pickeeId, false, currentTime, scheduledUpdateTime.getOrElse(currentTime),
        scheduledUpdateTime.isEmpty, p.cost, applyWildcard
      ).executeInsert()
  }

  override def setProcessed(transferId: Long)(implicit c: Connection): Long = {
    SQL("update transfer set processed = true where transfer_id = $transferId").executeUpdate()
  }
}

