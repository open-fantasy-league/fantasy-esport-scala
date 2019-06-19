package v1.transfer

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import anorm._
import play.api.db._
import models._
import play.api.mvc.Result
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo

class TransferExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TransferRepo{
  def getUserTransfer(userId: Long, processed: Option[Boolean])(implicit c: Connection): Iterable[TransferRow]
  def changeTeam(userId: Long, toBuyCardIds: Set[Long], toSellCardIds: Set[Long],
                 oldTeamCardIds: Set[Long], periodStart: Int, periodEnd: Option[Int]
                )(implicit c: Connection): Unit
  def pickeeLimitsInvalid(leagueId: Long, newTeamIds: Set[Long])(implicit c: Connection): Option[(String, Int)]
  def insert(
              userId: Long, internalPickeeId: Long, isBuy: Boolean, currentTime: LocalDateTime,
              scheduledUpdateTime: LocalDateTime, processed: Boolean, price: BigDecimal, applyWildcard: Boolean
            )(implicit c: Connection): Long
  def setProcessed(transferId: Long)(implicit c: Connection): Long
  def generateCard(leagueId: Long, userId: Long, pickeeId: Long, colour: String)(implicit c: Connection): CardOut
  def moneyAfterPack(userId: Long)(implicit c: Connection): Double
  def buyCardPack(leagueId: Long, userId: Long)(implicit c: Connection): Either[String, Iterable[CardOut]]
  def generateCardPack(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[CardOut]
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
                           oldTeamCardIds: Set[Long], periodStart: Int, periodEnd: Option[Int]
                         )(implicit c: Connection) = {
    if (toSellCardIds.nonEmpty) {
      // TODO is this logic legit?
      if (periodEnd.isDefined) {
        val q =
          """delete from team where card_id in ({toSellIds}) AND timespan = int4range({periodStart}, {periodEnd})"""
        SQL(q).on("periodStart" -> periodStart, "periodEnd" -> periodEnd, "toSellIds" -> toSellCardIds)
          .executeUpdate()
      }
      else{
        val q =
          """update team set timespan = int4range(lower(timespan), {periodStart}) where card_id in ({toSellIds})
            |AND timespan @> int4range({periodStart}, {periodEnd})""".stripMargin
        SQL(q).on("periodStart" -> periodStart, "periodEnd" -> periodEnd, "toSellIds" -> toSellCardIds)
          .executeUpdate()
      }
    }
    println(s"""Ended current team pickees: ${toSellCardIds.mkString(", ")}""")
    SQL("update useru set change_tstamp = null where user_id = {userId};").on("userId" -> userId).executeUpdate()
    print(s"""Buying: ${toBuyCardIds.mkString(", ")}""")
    toBuyCardIds.map(t => {
      SQL("insert into team(card_id, timespan) values({cardId}, int4range({periodStart}, {periodEnd})) returning team_id;").
        on("cardId" -> t, "periodStart" -> periodStart, "periodEnd" -> periodEnd).executeInsert().get
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

  override def generateCard(leagueId: Long, userId: Long, pickeeId: Long, colour: String)(implicit c: Connection): CardOut= {
    val rnd = scala.util.Random
    val newCard = SQL(
      """insert into card(user_id, pickee_id, colour) values({userId},{pickeeId},{colour})
        |returning card_id, user_id, pickee_id, colour""".stripMargin
    ).on("userId" -> userId, "pickeeId" -> pickeeId, "colour" -> colour).executeInsert(CardRow.parser.single)
    val statFieldIds = leagueRepo.getScoringStatFieldsForPickee(leagueId, pickeeId).map(_.statFieldId).toArray
    var randomStatFieldIds = scala.collection.mutable.Set[Long]()
    val pickeeWithLimits = pickeeRepo.getPickeeLimits(pickeeId)
    val bonuses = colour match {
      case "GOLD" => {
        while (randomStatFieldIds.size < 3){
          randomStatFieldIds += statFieldIds(rnd.nextInt(statFieldIds.length))  // TODO check this +1
        }
        randomStatFieldIds.map(sfid => {
          // leads to random from 1.10, 1.15, 1.20, or x0.25 0.5 0.75 for negative
          val isNegative = leagueRepo.getPointsForStat(sfid, pickeeRepo.getPickeeLimitIds(pickeeId)).get < 0.0
          val multiplier = if (isNegative) (rnd.nextInt(3) + 1).toDouble * 0.25 else {
            (((rnd.nextInt(3) + 2) * 5).toDouble / 100.0) + 1.0
          }
          insertCardBonus(newCard.cardId, sfid, multiplier)
          CardBonusMultiplierRow(sfid, leagueRepo.getStatFieldName(sfid).get, multiplier)
        }).toList
      }
      case "SILVER" => {
        while (randomStatFieldIds.size < 2){
          randomStatFieldIds += statFieldIds(rnd.nextInt(statFieldIds.length))  // TODO check this +1
        }
        randomStatFieldIds.map(sfid => {
          val isNegative = leagueRepo.getPointsForStat(sfid, pickeeRepo.getPickeeLimitIds(pickeeId)).get < 0.0
          // leads to random from 1.05, 1.10, 1.15 or x0.4 0.6 0.8 for negative
          val multiplier = if (isNegative) (rnd.nextInt(3) + 2).toDouble * 0.2 else {
            (((rnd.nextInt(3) + 1) * 5).toDouble / 100.0) + 1.0
          }
          insertCardBonus(newCard.cardId, sfid, multiplier)
          CardBonusMultiplierRow(sfid, leagueRepo.getStatFieldName(sfid).get, multiplier)
        }).toList
      }
      case _ => List[CardBonusMultiplierRow]()
    }

    CardOut(
      newCard.cardId, pickeeWithLimits.pickee.internalPickeeId, pickeeWithLimits.pickee.externalPickeeId,
      pickeeWithLimits.pickee.pickeeName, pickeeWithLimits.pickee.price, newCard.colour,
      // if have multiple limits, get the bonus for each row, so need to filter them out of map to not get dupes
      bonuses, pickeeWithLimits.limits
    )
  }

  override def moneyAfterPack(userId: Long)(implicit c: Connection): Double = {
    SQL"""
         select (money - card_pack_cost) as new_money from useru join league using(league_id) where user_id = $userId
      """.as(SqlParser.double("new_money").single)
  }

  override def buyCardPack(leagueId: Long, userId: Long)(implicit c: Connection): Either[String, Iterable[CardOut]] = {
    // relies on contrainst not letting user money negative
    if (moneyAfterPack(userId) < -0.001) Left("Not enought money to buy pack")
    else {
      SQL"""
         update useru set money = money - card_pack_cost
         from league where useru.league_id = league.league_id AND user_id = $userId
       """.executeUpdate()
      Right(generateCardPack(leagueId, userId))
    }
  }

  override def generateCardPack(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[CardOut] = {
    val pickeeIds = pickeeRepo.getRandomPickeesFromDifferentFactions(leagueId).toArray
    for {
        i <- 0 until 7
        colour = scala.util.Random.nextInt(10) match {
          case x if x < 6 => "BRONZE"
          case x if x < 9 => "SILVER"
          case x if x < 10 => "GOLD"
          case _ => "BRONZE"
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

