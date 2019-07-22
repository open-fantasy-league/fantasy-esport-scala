package v1.transfer

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import anorm._
import models._
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo

class TransferExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TransferRepo{
  def getUserTransfer(userId: Long)(implicit c: Connection): Iterable[TransferRow]
  def getNextPeriodScheduledChanges(userId: Long, currentPeriod: Int)(implicit c: Connection): ScheduledChangesOut
  def changeTeam(userId: Long, toBuyCardIds: Set[Long], toSellCardIds: Set[Long],
                 oldTeamCardIds: Set[Long], periodStart: Int, periodEnd: Option[Int]
                )(implicit c: Connection): Unit
  def pickeeLimitsInvalid(leagueId: Long, newTeamIds: Set[Long])(implicit c: Connection): Option[(String, Int)]
  def insert(
              userId: Long, internalPickeeId: Long, isBuy: Boolean, currentTime: LocalDateTime,
              periodStart: Int, periodEnd: Option[Int], price: BigDecimal, applyWildcard: Boolean
            )(implicit c: Connection): Long
  def generateCard(leagueId: Long, userId: Long, pickeeId: Long, colour: String)(implicit c: Connection): CardOut
  def moneyAfterPack(userId: Long)(implicit c: Connection): Double
  def buyCardPack(leagueId: Long, userId: Long, packSize: Int, packCost: BigDecimal)(implicit c: Connection): Either[String, Iterable[CardOut]]
  def generateCardPack(leagueId: Long, userId: Long, packSize: Int)(implicit c: Connection): Iterable[CardOut]
  def insertCardBonus(cardId: Long, statFieldId: Long, multiplier: Double)(implicit c: Connection)
  def recycleCards(leagueId: Long, userId: Long, cardId: List[Long], recycleValue: BigDecimal)(implicit c: Connection): Boolean
}

@Singleton
class TransferRepoImpl @Inject()(pickeeRepo: PickeeRepo)(implicit ec: TransferExecutionContext, leagueRepo: LeagueRepo) extends TransferRepo{
  override def getUserTransfer(userId: Long)(implicit c: Connection): Iterable[TransferRow] = {
    SQL(
      s"""
        |select transfer_id, user_id, p.pickee_id as internal_pickee_id, p.external_pickee_id,
        | p.pickee_name, is_buy,
        | time_made, transfer.price, was_wildcard
        | from transfer join pickee p using(pickee_id) where user_id = $userId;
      """.stripMargin).as(TransferRow.parser.*)
  }

  override def getNextPeriodScheduledChanges(userId: Long, currentPeriod: Int)(implicit c: Connection): ScheduledChangesOut = {
    val toSell = SQL""" select card_id, pickee_id from team
                    join card using(card_id) where user_id = $userId and upper(timespan) = ${currentPeriod + 1}
      """.as((SqlParser.long("card_id") ~ SqlParser.long("pickee_id")).*)
    val toBuy = SQL""" select card_id, pickee_id from team
                    join card using(card_id) where user_id = $userId and lower(timespan) = ${currentPeriod + 1}
      """.as((SqlParser.long("card_id") ~ SqlParser.long("pickee_id")).*)
    ScheduledChangesOut(
      toBuy.map({case cardId ~ pickeeId => ChangeOut(pickeeId, cardId)}),
      toSell.map({case cardId ~ pickeeId => ChangeOut(pickeeId, cardId)})
    )
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
        SQL"""delete from team where lower(timespan) = $periodStart AND card_id in ($toSellCardIds)""".executeUpdate()
        val q =
          """update team set timespan = int4range(lower(timespan), {periodStart}) where card_id in ({toSellIds})
            |AND timespan @> int4range({periodStart}, {periodEnd})""".stripMargin
        SQL(q).on("periodStart" -> periodStart, "periodEnd" -> periodEnd, "toSellIds" -> toSellCardIds)
          .executeUpdate()
      }
    }
    println(s"""Ended current team pickees: ${toSellCardIds.mkString(", ")}""")
    print(s"""Buying: ${toBuyCardIds.mkString(", ")}""")
    toBuyCardIds.map(t => {
      SQL("insert into team(card_id, timespan) values({cardId}, int4range({periodStart}, {periodEnd})) returning team_id;").
        on("cardId" -> t, "periodStart" -> periodStart, "periodEnd" -> periodEnd).executeInsert().get
    })
    println("Inserted new team")
  }

  override def pickeeLimitsInvalid(leagueId: Long, newTeamIds: Set[Long])(implicit c: Connection): Option[(String, Int)] = {
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
                       periodStart: Int, periodEnd: Option[Int], price: BigDecimal, applyWildcard: Boolean
                     )(implicit c: Connection): Long = {
    SQL"""
        insert into transfer(user_id, pickee_id, is_buy, time_made, timespan, price, was_wildcard)
        values($userId, $internalPickeeId, $isBuy, $currentTime, int4range($periodStart, $periodEnd), $price, $applyWildcard)
        returning transfer_id;
        """.executeInsert().get
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
          val statField = leagueRepo.getStatField(sfid).get
          CardBonusMultiplierRow(sfid, statField.name, multiplier, statField.description)
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
          val statField = leagueRepo.getStatField(sfid).get
          CardBonusMultiplierRow(sfid, statField.name, multiplier, statField.description)
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
         select (money - pack_cost) as new_money from useru join league using(league_id) join card_system using(league_id)
         where user_id = $userId
      """.as(SqlParser.double("new_money").single)
  }

  override def buyCardPack(leagueId: Long, userId: Long, packSize: Int, packCost: BigDecimal)(implicit c: Connection): Either[String, Iterable[CardOut]] = {
    // relies on contrainst not letting user money negative
    if (moneyAfterPack(userId) < -0.001) Left("Not enought money to buy pack")
    else {
      SQL"""
         update useru set money = money - $packCost WHERE user_id = $userId
       """.executeUpdate()
      Right(generateCardPack(leagueId, userId, packSize))
    }
  }

  override def generateCardPack(leagueId: Long, userId: Long, packSize: Int)(implicit c: Connection): Iterable[CardOut] = {
    val pickeeIds = pickeeRepo.getRandomPickeesFromDifferentFactions(leagueId, packSize).toArray
    for {
        i <- 0 until packSize
        colour = scala.util.Random.nextInt(10) match {
          case x if x < 5 => "BRONZE"
          case x if x < 8 => "SILVER"
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

  override def recycleCards(leagueId: Long, userId: Long, cardIds: List[Long], recycleValue: BigDecimal)(implicit c: Connection): Boolean = {
    val updatedCount = SQL"""update card set recycled = true where card_id IN ($cardIds) and user_id = $userId""".executeUpdate()
    if (updatedCount == 0){
      return false
    }
    SQL"""update useru set money = money + $recycleValue * $updatedCount WHERE user_id = $userId""".executeUpdate()
    true
  }
}

