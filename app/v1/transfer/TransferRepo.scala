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
  def changeTeam(userId: Long, toBuyCardIds: Set[Long], toSellCardIds: Set[Long],
                 oldTeamCardIds: Set[Long], time: LocalDateTime
                )(implicit c: Connection): Unit
  def pickeeLimitsInvalid(leagueId: Long, newTeamIds: Set[Long])(implicit c: Connection): Option[(String, Int)]
  def insert(
              userId: Long, internalPickeeId: Long, isBuy: Boolean, currentTime: LocalDateTime,
              scheduledUpdateTime: LocalDateTime, processed: Boolean, price: BigDecimal, applyWildcard: Boolean
            )(implicit c: Connection): Long
  def setProcessed(transferId: Long)(implicit c: Connection): Long
  def generateCard(leagueId: Long, userId: Long, pickeeId: Long, colour: String)(implicit c: Connection): CardRow
  def generateCardPack(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[CardRow]
  def insertCardBonus(cardId: Long, statFieldId: Long, multiplier: Double)(implicit c: Connection)
  def recycleCard(leagueId: Long, userId: Long, cardId: Long)(implicit c: Connection): Boolean
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
  override def changeTeam(userId: Long, toBuyCardIds: Set[Long], toSellCardIds: Set[Long],
                           oldTeamCardIds: Set[Long], time: LocalDateTime
                         )(implicit c: Connection) = {
    if (toSellCardIds.nonEmpty) {
      val q =
        """update team t set timespan = tstzrange(lower(timespan), {time})
    where upper(t.timespan) is NULL and t.card_id in ({toSellIds});
    """
      SQL(q).on("time" -> time, "toSellIds" -> toSellCardIds).executeUpdate()
    }
    println(s"""Ended current team pickees: ${toSellCardIds.mkString(", ")}""")
    SQL("update useru set change_tstamp = null where user_id = {userId};").on("userId" -> userId).executeUpdate()
    print(s"""Buying: ${toBuyCardIds.mkString(", ")}""")
    toBuyCardIds.map(t => {
      SQL("insert into team(card_id, timespan) values({cardId}, tstzrange({time}, null)) returning team_id;").
        on("cardId" -> t, "time" -> time).executeInsert().get
    })
    println("Inserted new team")
  }

  override def pickeeLimitsInvalid(leagueId: Long, newTeamIds: Set[Long])(implicit c: Connection): Option[(String, Int)] = {
    // TODO need to check this againbst something. doesnt work right now
    if (newTeamIds.isEmpty) return None
    val parser =
      (SqlParser.str("name") ~ SqlParser.int("limmax")).map {
        case name ~ max_ => name -> max_
      }
    val q =
      """select l.name, coalesce(lt.max, l.max) as limmax from pickee p
        | join pickee_limit pl using(pickee_id)
        | join "limit" l using(limit_id)
        | join limit_type lt using(limit_type_id)
        | where p.league_id = {leagueId} and p.pickee_id in ({newTeamIds})
        | group by (lt."max", l.limit_id) having count(*) > coalesce(lt."max", l.max)
        | limit 1;
      """.stripMargin
    SQL(q).on("leagueId" -> leagueId, "newTeamIds" -> newTeamIds).as(parser.singleOpt)
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
      """insert into card(user_id, pickee_id, colour) values({userId},{pickeeId},{colour})
        |returning card_id, user_id, pickee_id, colour""".stripMargin
    ).on("userId" -> userId, "pickeeId" -> pickeeId, "colour" -> colour).executeInsert(CardRow.parser.single)
    val statFieldIds = leagueRepo.getScoringStatFieldsForPickee(leagueId, pickeeId).map(_.statFieldId).toArray
    var randomStatFieldIds = scala.collection.mutable.Set[Long]()
    colour match {
      case "GOLD" => {
        while (randomStatFieldIds.size < 2){
          randomStatFieldIds += statFieldIds(rnd.nextInt(statFieldIds.length))  // TODO check this +1
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
        insertCardBonus(newCard.cardId, statFieldIds(rnd.nextInt(statFieldIds.length)), multiplier)
      }
      case _ => ()
    }
    newCard
  }

  override def generateCardPack(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[CardRow] = {
    val pickeeIds = pickeeRepo.getRandomPickeesFromDifferentFactions(leagueId).toArray
    for {
        i <- 0 until 7
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
    ).on ("cardId" -> cardId, "statFieldId" -> statFieldId, "multiplier" -> multiplier).executeInsert()
  }

  override def recycleCard(leagueId: Long, userId: Long, cardId: Long)(implicit c: Connection): Boolean = {
    val updatedCount = SQL"""update card set recycled = true where card_id = $cardId and user_id = $userId;""".executeUpdate()
    if (updatedCount == 0){
      return false
    }
    SQL"""update useru set money = money + league.recycle_value from league where useru.league_id = league.league_id""".executeUpdate()
    true
  }
}

