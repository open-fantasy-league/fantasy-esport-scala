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
import v1.pickee.PickeeRepo


class TransferExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TransferRepo{
  def getUserTransfer(userId: Long, processed: Option[Boolean])(implicit c: Connection): Iterable[TransferRow]
  def processUserTransfer(userId: Long)(implicit c: Connection): Unit
  def changeTeam(userId: Long, toBuyIds: Set[Long], toSellIds: Set[Long],
                 oldTeamIds: Set[Long], time: LocalDateTime
                )(implicit c: Connection): Unit
  def pickeeLimitsValid(leagueId: Long, newTeamIds: Set[Long])(implicit c: Connection): Boolean
  def insert(
              userId: Long, internalPickeeId: Long, isBuy: Boolean, currentTime: LocalDateTime,
              scheduledUpdateTime: LocalDateTime, processed: Boolean, price: BigDecimal, applyWildcard: Boolean
            )(implicit c: Connection): Long
  def setProcessed(transferId: Long)(implicit c: Connection): Long
  def generateCard(leagueId: Long, userId: Long, pickeeId: Long, colour: String)(implicit c: Connection): CardRow
  def generateCardPack(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[CardRow]
  def insertCardBonus(cardId: Long, statFieldId: Long, multiplier: Double)(implicit c: Connection)
}

@Singleton
class TransferRepoImpl @Inject()(pickeeRepo: PickeeRepo)(implicit ec: TransferExecutionContext, leagueRepo: LeagueRepo) extends TransferRepo{
  override def getUserTransfer(userId: Long, processed: Option[Boolean])(implicit c: Connection): Iterable[TransferRow] = {
    val processedFilter = if (processed.isEmpty) "" else s"and processed = ${processed.get}"
    SQL(
      s"""
        |select transfer_id, user_id, p.pickee_id as internal_pickee_id, p.external_pickee_id,
        | p.pickee_name, is_buy,
        | time_made, scheduled_for, processed, transfer.price, was_wildcard
        | from transfer join pickee p using(pickee_id) where user_id = $userId $processedFilter;
      """.stripMargin).as(TransferRow.parser.*)
  }
  // ALTER TABLE team ALTER COLUMN id SET DEFAULT nextval('team_seq');
  override def changeTeam(userId: Long, toBuyIds: Set[Long], toSellIds: Set[Long],
                           oldTeamIds: Set[Long], time: LocalDateTime
                         )(implicit c: Connection) = {
      val newPickees: Set[Long] = (oldTeamIds -- toSellIds) ++ toBuyIds
    if (toSellIds.nonEmpty) {
      val q =
        """update team t set timespan = tstzrange(lower(timespan), {time})
    where t.user_id = {userId} and upper(t.timespan) is NULL and t.pickeeId in ({toSellIds});
    """
      SQL(q).on("userId" -> userId, "time" -> time, "toSellIds" -> toSellIds).executeUpdate()
    }
    println(s"""Ended current team pickees: ${toSellIds.mkString(", ")}""")
    SQL("update useru set change_tstamp = null where user_id = {userId};").on("userId" -> userId).executeUpdate()
    print(s"""Buying: ${toBuyIds.mkString(", ")}""")
    toBuyIds.map(t => {
      SQL("insert into team(user_id, pickee_id, timespan) values({userId}, {pickeeId}, tstzrange({time}, null)) returning team_id;").
        on("userId" -> userId, "pickeeId" -> t, "time" -> time).executeInsert().get
    })
    println("Inserted new team")
  }

  override def processUserTransfer(userId: Long)(implicit c: Connection)  = {
    val now = LocalDateTime.now()
    // TODO need to lock here?
    // TODO map and filter together
    val transfers = getUserTransfer(userId, Some(false))
    // TODO single iteration
    val toSellIds = transfers.filter(!_.isBuy).map(_.internalPickeeId).toSet
    val toBuyIds = transfers.filter(_.isBuy).map(_.internalPickeeId).toSet
      val q =
        """select pickee_id from team t where t.user_id = {userId} and upper(t.timespan) is NULL;
              """
      val oldTeamIds = SQL(q).on("userId" -> userId).as(SqlParser.scalar[Long].*).toSet
      changeTeam(userId, toBuyIds, toSellIds, oldTeamIds, now)
      transfers.map(t => setProcessed(t.transferId))
  }

  override def pickeeLimitsValid(leagueId: Long, newTeamIds: Set[Long])(implicit c: Connection): Boolean = {
    // TODO need to check this againbst something. doesnt work right now
    if (newTeamIds.isEmpty) return true
    val q =
      """select not exists (select 1 from pickee p
        | join limit_type lt using(league_id)
        | join "limit" l using(limit_type_id)
        | where p.league_id = {leagueId} and p.pickee_id in ({newTeamIds}) group by (lt."max", l.limit_id) having count(*) > lt."max");
      """.stripMargin
    SQL(q).on("leagueId" -> leagueId, "newTeamIds" -> newTeamIds).as(SqlParser.scalar[Boolean].single)
  }

  override def insert(
                       userId: Long, internalPickeeId: Long, isBuy: Boolean, currentTime: LocalDateTime,
                       scheduledUpdateTime: LocalDateTime, processed: Boolean, price: BigDecimal, applyWildcard: Boolean
                     )(implicit c: Connection): Long = {
    SQL(
      """
        |insert into transfer(user_id, pickee_id, is_buy, time_made, scheduled_for, processed, price, was_wildcard)
        |values({userId}, {internalPickeeId}, {isBuy}, {currentTime}, {scheduledUpdateTime}, {processed}, {price}, {applyWildcard})
        |returning transfer_id;
        |""".stripMargin
    ).on("userId" -> userId, "internalPickeeId" -> internalPickeeId, "isBuy" -> isBuy, "currentTime" -> currentTime,
      "scheduledUpdateTime" -> scheduledUpdateTime, "processed" -> processed, "price" -> price, "applyWildcard" -> applyWildcard
      ).executeInsert().get
  }

  override def setProcessed(transferId: Long)(implicit c: Connection): Long = {
    SQL(s"update transfer set processed = true where transfer_id = $transferId").executeUpdate()
  }

  override def generateCard(leagueId: Long, userId: Long, pickeeId: Long, colour: String)(implicit c: Connection): CardRow = {
    val rnd = scala.util.Random
    val newCard = SQL(
      "insert into card(user_id, pickeeId, colour) values({userId},{pickeeId},{colour})"
    ).on("userId" -> userId, "pickeeId" -> pickeeId, "colour" -> colour).executeInsert(CardRow.parser.single)
    val statFieldIds = leagueRepo.getScoringStatFieldsForPickee(leagueId).map(_.statFieldId)
    var randomStatFieldIds = Set[Int]()
    colour match {
      case "GOLD" => {
        while (randomStatFieldIds.size < 2){
          randomStatFieldIds += rnd.nextInt(statFieldIds.size+1)  // TODO check this +1
        }
        randomStatFieldIds.foreach(sfid => {
          // leads to random from 1.15, 1.20, 1.25
          val multiplier = (((rnd.nextInt(3) + 3) * 5).toDouble / 100.0) + 1.0
          insertCardBonus(newCard.cardId, sfid, multiplier)
        })
      }
      case "SILVER" => {
        // leads to random from 1.05, 1.10, 1.15
        val multiplier = (((rnd.nextInt(3) + 1) * 5).toDouble / 100.0) + 1.0
        insertCardBonus(newCard.cardId, rnd.nextInt(statFieldIds.size+1), multiplier)
      }
      case _ => ()
    }
    newCard
  }

  override def generateCardPack(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[CardRow] = {
    val pickeeIds = pickeeRepo.getRandomPickeesFromDifferentFactions(leagueId)
    for {
        i <- 0 to 7
        colour = scala.util.Random.nextInt(10) match {
          case x if x < 6 => "GREY"
          case x if x < 9 => "SILVER"
          case x if x < 10 => "GOLD"
          case _ => "GREY"
        }
      }
      yield generateCard(leagueId, userId, pickeeIds(i), colour)
  }

  override def insertCardBonus(cardId: Long, statFieldId: Long, multiplier: Double)(implicit c: Connection) = {
    SQL (
      "insert into card_bonus_multiplier(card_id, stat_field_id, multiplier) values({cardId},{statFieldId},{multiplier})"
    ).on ("cardId" -> cardId, "statFieldId" -> statFieldId, "multiplier" -> multiplier).executeInsert ()
  }
}

