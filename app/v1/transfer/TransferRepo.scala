package v1.transfer

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, InternalServerError}
import anorm.Macro.ColumnNaming
import play.api.libs.concurrent.CustomExecutionContext
import anorm._
import models._
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo
import v1.team.TeamRepo
import v1.user.UserRepo

case class DraftQueueInternalRow(draftQueueId: Long, pickeeId: Long, childId: Option[Long])
case class DraftInfo(numMissed: Int, unstarted: Boolean, draftStart: LocalDateTime)

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
  def recycleCards(leagueId: Long, userId: Long, cardId: List[Long], recycleValue: BigDecimal)
                  (implicit c: Connection): Int
  def appendDraftQueue(userId: Long, pickeeId: Long)(implicit c: Connection)
  def deleteDraftQueue(userId: Long, pickeeId: Long)(implicit c: Connection)
  def draftQueueAutopick(userId: Long, setAutopick: Boolean)(implicit c: Connection)
  def validateLimits(newTeamIds: Set[Long], leagueId: Long)(implicit c: Connection): Either[Result, Any]
  def draftPickee(userId: Long, leagueId: Long, internalPickeeId: Long)
                 (implicit c: Connection): Either[String, UserPickee]
  def draftUsersRecursivelyIfHaveQueue(leagueId: Long, userIds: Iterable[Long], takenCardIds: Set[Long])
                                          (implicit c: Connection): Map[Long, Long]
  def getDraftQueue(userId: Long)(implicit c: Connection): Iterable[DraftQueueRow]
  def getAutopick(userId: Long)(implicit c: Connection): Boolean
  def getDraftOrder(leagueId: Long)(implicit c: Connection): Iterable[DraftOrderRow]
  def getDraftOrderCount(leagueId: Long)(implicit c: Connection): Int

}

@Singleton
class TransferRepoImpl @Inject()(pickeeRepo: PickeeRepo, userRepo: UserRepo, teamRepo: TeamRepo)(implicit ec: TransferExecutionContext, leagueRepo: LeagueRepo) extends TransferRepo{
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

  override def recycleCards(leagueId: Long, userId: Long, cardIds: List[Long], recycleValue: BigDecimal)(implicit c: Connection): Int = {
    val updatedCount = SQL"""update card set recycled = true where card_id = ANY(ARRAY[$cardIds]::bigint[]) and user_id = $userId""".executeUpdate()
    if (updatedCount == 0){
      return updatedCount
    }
    SQL"""update useru set money = money + $recycleValue * $updatedCount WHERE user_id = $userId""".executeUpdate()
    return updatedCount
  }

  override def appendDraftQueue(userId: Long, pickeeId: Long)(implicit c: Connection) = {
    val newId =
      SQL"""
           insert into draft_queue(user_id, pickee_ids) values($userId, ARRAY[$pickeeId])
           ON CONFLICT(user_id) DO UPDATE set pickee_ids = draft_queue.pickee_ids || $pickeeId
        """.executeInsert()
  }

  override def deleteDraftQueue(userId: Long, pickeeId: Long)(implicit c: Connection) = {
    // TODO delete on non-existing
    SQL"""update draft_queue set pickee_ids = array_remove(pickee_ids, $pickeeId) where user_id = $userId"""
  }

  override def draftQueueAutopick(userId: Long, setAutopick: Boolean)(implicit c: Connection) = {
    SQL"""update draft_queue set autopick = $setAutopick where user_id = $userId"""
  }

//  override def draftPickee(userId: Long, leagueId: Long, internalPickeeId: Long)
//                          (implicit c: Connection): Either[String, Iterable[(UserRow, PickeeRow)]] = {
//    // FInd out since the last call how many users must have missed their go
//    val parser: RowParser[DraftInfo] = Macro.namedParser[DraftInfo](ColumnNaming.SnakeCase)
//    val draftInfo =
//      SQL"""
//           select (extract epoch from (next_draft_deadline - now()) / choice_timer) as num_missed, draft_start > now() as unstarted,
//           draft_start
//           from draft_system where league_id = $leagueId
//        """.as(parser.single)
//    // Remove them all from the draft-order
//    if (draftInfo.unstarted) {
//      return Left(s"Draft doesn't start until ${draftInfo.draftStart}")
//    }
//    if (draftInfo.numMissed > 0){
//      SQL"""update draft_order set user_ids = user_ids[${draftInfo.numMissed}:] where league_id = $leagueId".executeUpdate()""".executeUpdate()
//    }
//    val nextDrafterIds: List[Long] = SQL"select unnest(user_ids) as user_id from draft_order where league_id = $leagueId"
//      .as(SqlParser.long("user_id").*).toList
//    if (nextDrafterIds.isEmpty || nextDrafterIds.head != userId)
//      return Left("User not next in draft order")
//    var takenCards =
//      SQL"""select pickee_id from card join pickee using(pickee_id) where league_id = $leagueId""".as(
//        SqlParser.long("pickee_id").*
//      ).toSet
//    val pickeeId = if (internalPickeeId.isDefined){internalPickeeId}
//    else {
//      val watchlist = SQL"select pickee_ids from draft_queue where user_id = $userId".
//        as(SqlParser.array[Long]("pickee_ids").single)
//      val firstUntakenPickeeId = watchlist.find(w => !takenCards.contains(w))
//
//      firstUntakenPickeeId.map(pid => {
//        SQL"insert into card(user_id, pickee_id) values($userId, $pid)".executeInsert()
//        SQL"update from draft_queue set pickee_ids = array_remove(pickee_ids, $pid) where user_id = $userId".executeUpdate()
//        pid
//      })
//    }
//    takenCards = takenCards + pickeeId.get
//
//    SQL"update draft_order set user_ids = user_ids[1:] where league_id = $leagueId returning user_ids".executeUpdate()
//    val drafted: Map[Long, Long] = if (pickeeId.isDefined) {
//      Map(userId -> pickeeId.get) ++ draftUsersRecursivelyIfHaveQueue(leagueId, nextDrafterIds, takenCards)
//    } else Map()
//    // Update deadline for next user in list
//    SQL"""update draft_system
//         set next_draft_deadline = now() + interval '1 second' * choice_timer
//         where league_id = $leagueId""".executeUpdate()
//    val draftedOut = if (drafted.nonEmpty){
//      // use fold as does in single pass (toMap variants would be double pass)
//      val users = userRepo.getUsers(drafted.keys.toList).foldLeft(Map[Long, UserRow]()){(m, u) => m + (u.userId -> u)}
//      val pickees = pickeeRepo.getPickees(drafted.values.toList).foldLeft(Map[Long, PickeeRow]()){(m, u) => m + (u.internalPickeeId -> u)}
//      drafted.map({case (k, v) => (users(k), pickees(v))}).toList
//    } else List()
//    Right(draftedOut)
//  }

  override  def validateLimits(newTeamIds: Set[Long], leagueId: Long)(implicit c: Connection): Either[Result, Any] = {
    // TODO errrm this is a bit messy
    pickeeLimitsInvalid(leagueId, newTeamIds) match {
      case None => Right(true)
      case Some((name, max_)) => Left(BadRequest(
        f"Exceeds $name limit: max $max_ allowed"  // TODO what limit does it exceed
      ))
    }
  }

  override def draftPickee(userId: Long, leagueId: Long, internalPickeeId: Long)
                          (implicit c: Connection): Either[String, UserPickee] = {
    // FInd out since the last call how many users must have missed their go
    val parser: RowParser[DraftInfo] = Macro.namedParser[DraftInfo](ColumnNaming.SnakeCase)
    val draftInfo =
      SQL"""
           select (GREATEST(0, floor(extract (epoch from (now() - next_draft_deadline)) / choice_timer)::integer)) as num_missed, draft_start > now() as unstarted,
           draft_start
           from draft_system where league_id = $leagueId
        """.as(parser.single)
    // Remove them all from the draft-order
    if (draftInfo.unstarted) {
      return Left(s"Draft doesn't start until ${draftInfo.draftStart}")
    }
    if (draftInfo.numMissed > 0){
      SQL"""update draft_order set user_ids = user_ids[${draftInfo.numMissed}:] where league_id = $leagueId".executeUpdate()""".executeUpdate()
    }
    val nextDrafterIds: List[Long] = SQL"select unnest(user_ids) as user_id from draft_order where league_id = $leagueId"
      .as(SqlParser.long("user_id").*)
    val currentTeam = teamRepo.getUserCards(leagueId, Some(userId), None, None, false)
    if (nextDrafterIds.isEmpty || nextDrafterIds.head != userId)
      return Left("User not next in draft order")
    // TODO for yield this shit
    val validated = validateLimits(currentTeam.map(_.internalPickeeId).toSet + internalPickeeId, leagueId)
    if (validated.isRight) {

      SQL"update draft_order set user_ids = user_ids[1:] where league_id = $leagueId returning user_ids".executeUpdate()
      // Update deadline for next user in list
      SQL"""update draft_system
         set next_draft_deadline = now() + interval '1 second' * choice_timer
         where league_id = $leagueId""".executeUpdate()
      val user = userRepo.getUsers(List(userId)).head
      val pickee = pickeeRepo.getPickees(List(internalPickeeId)).head
      Right(UserPickee(user, pickee))
    } else Left("fuck")
  }

  override def draftUsersRecursivelyIfHaveQueue(leagueId: Long, userIds: Iterable[Long], takenCardIds: Set[Long])
                                       (implicit c: Connection): Map[Long, Long] = {
    val userId = userIds.head
    val watchlist = SQL"select pickee_ids from draft_queue where user_id = $userId".
      as(SqlParser.array[Long]("pickee_ids").single)
    val firstUntakenPickeeId = watchlist.find(w => !takenCardIds.contains(w))
    if (firstUntakenPickeeId.isDefined) {
      SQL"insert into card(user_id, pickee_id) values($userId, $firstUntakenPickeeId)".executeInsert()
      SQL"update from draft_queue set pickee_ids = array_remove(pickee_ids, $firstUntakenPickeeId) where user_id = $userId".executeUpdate()
      Map[Long, Long](userId -> firstUntakenPickeeId.get) ++
        draftUsersRecursivelyIfHaveQueue(leagueId, userIds.tail, takenCardIds + firstUntakenPickeeId.get)
    } else Map()
  }

  override def getDraftQueue(userId: Long)(implicit c: Connection): Iterable[DraftQueueRow] = {
    SQL"""
         select external_pickee_id, pickee_name, pickee_id from pickee
         join (select unnest(pickee_ids) as pickee_id from draft_queue where user_id = $userId) sub using(pickee_id);
      """.as(DraftQueueRow.parser.*)
  }

  override def getAutopick(userId: Long)(implicit c: Connection): Boolean = {
    SQL"select autopick from draft_queue where user_id = $userId".as(SqlParser.bool("autopick").single)
  }

  override def getDraftOrder(leagueId: Long)(implicit c: Connection): Iterable[DraftOrderRow] = {
    SQL"""
          select external_user_id, user_id, username from useru
          join (select unnest(user_ids) as user_id from draft_order where league_id = $leagueId) sub using(user_id);
      """.as(DraftOrderRow.parser.*)
  }

  override def getDraftOrderCount(leagueId: Long)(implicit c: Connection): Int = {
    SQL"""
         select array_length(user_ids, 1) as countu from draft_order where league_id = $leagueId
      """.as(SqlParser.int("countu").single)
  }
}

