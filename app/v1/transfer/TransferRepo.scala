package v1.transfer

import java.sql.Connection
import java.time.LocalDateTime

import scala.math.min
import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, InternalServerError}
import anorm.Macro.ColumnNaming
import play.api.libs.concurrent.CustomExecutionContext
import anorm._
import models._
import play.api.Logger
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo
import v1.team.TeamRepo
import v1.user.UserRepo

import scala.util.Random

case class DraftQueueInternalRow(draftQueueId: Long, pickeeId: Long, childId: Option[Long])
case class DraftInfo(missed: Boolean, unstarted: Boolean, draftStart: LocalDateTime, nextDraftDeadline: LocalDateTime, paused: Boolean)
case class DraftSystem(leagueId: Long, nextDraftDeadline: LocalDateTime)
case class DatetimeRow(datetime: LocalDateTime)

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
  def buyCardPack(leagueId: Long, userId: Long, packSize: Int, packCost: BigDecimal)(implicit c: Connection):
    Either[String, Iterable[CardOut]]
  def generateCardPack(leagueId: Long, userId: Long, packSize: Int)(implicit c: Connection): Iterable[CardOut]
  def insertCardBonus(cardId: Long, statFieldId: Long, multiplier: Double)(implicit c: Connection)
  def recycleCards(leagueId: Long, userId: Long, cardId: List[Long], recycleValue: BigDecimal)
                  (implicit c: Connection): Int
  def appendDraftQueue(userId: Long, pickeeId: Long)(implicit c: Connection)
  def deleteDraftQueue(userId: Long, pickeeId: Long)(implicit c: Connection)
  def draftQueueAutopick(userId: Long, setAutopick: Boolean)(implicit c: Connection)
  def validateLimits(newTeamIds: Set[Long], leagueId: Long)(implicit c: Connection): Either[Result, Any]
  def draftPickee(userId: Long, leagueId: Long, internalPickeeId: Long)
                 (implicit c: Connection): Either[Result, Iterable[UserPickee]]
  def getDraftQueue(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[DraftQueueRow]
  def getAutopick(userId: Long)(implicit c: Connection): Boolean
  def getDraftOrder(leagueId: Long)(implicit c: Connection): Iterable[DraftOrderRow]
  def getDraftOrderCount(leagueId: Long)(implicit c: Connection): Int

  def getDraftDeadlines()(implicit c: Connection): List[DraftSystem]
  def draftDeadlineReached(leagueId: Long)(implicit c: Connection): Option[LocalDateTime]
  def manualDraft(leagueId: Long, input: ManualDraftFormInput)(implicit  c: Connection): Either[Result, Iterable[CardOut]]
  def setDraftPaused(leagueId: Long, paused: Boolean)(implicit  c: Connection)
  def processWaiverPickees(leagueId: Long, teamSize: Int, periodVal: Int)(implicit c: Connection)

}

@Singleton
class TransferRepoImpl @Inject()(pickeeRepo: PickeeRepo, userRepo: UserRepo, teamRepo: TeamRepo)(implicit ec: TransferExecutionContext, leagueRepo: LeagueRepo) extends TransferRepo{
  private val logger = Logger("application")

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
    logger.info(s"Generating card for userId: $userId, pickeeId: $pickeeId, colour: $colour")
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
    SQL"""update draft_queue set pickee_ids = array_remove(pickee_ids, $pickeeId) where user_id = $userId""".executeUpdate()
  }

  override def draftQueueAutopick(userId: Long, setAutopick: Boolean)(implicit c: Connection) = {
    SQL"""update draft_queue set autopick = $setAutopick where user_id = $userId""".executeUpdate()
    // TODO should turning autopick on whilst it is your go trigger a draft of top of list?
//    if (setAutopick) {
//      val nextDrafterIds: List[Long] = SQL"select unnest(user_ids) as user_id from draft_order where league_id = $leagueId"
//        .as(SqlParser.long("user_id").*)
//      val takenCardIds = SQL"select pickee_id from pickee join card using(pickee_id) where league_id = $leagueId".
//        as(SqlParser.long("pickee_id").*).toSet
//      draftAutopickUsersRecursively(leagueId, nextDrafterIds, takenCardIds)
//      setNewDraftDeadline(leagueId)
//    }
  }

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
                          (implicit c: Connection): Either[Result, Iterable[UserPickee]] = {
    val draftInfo = getDraftInfo(leagueId)
    // min to avoid the skip someone with autopick on scenario
    if (draftInfo.missed) {
      sliceDrafts(leagueId, 1)
    }
    if (draftInfo.unstarted) {
      return Left(BadRequest(s"Draft doesn't start until ${draftInfo.draftStart}"))
    }
    if (draftInfo.paused) {
      return Left(BadRequest(s"Draft currently paused. Commissioner needs to unpause"))
    }
    val nextDrafterIds: List[Long] = SQL"select unnest(user_ids) as user_id from draft_order where league_id = $leagueId"
      .as(SqlParser.long("user_id").*)

    if (nextDrafterIds.isEmpty || nextDrafterIds.head != userId)
      return Left(BadRequest("User not next in draft order"))
    val currentTeam = teamRepo.getUserCards(leagueId, Some(userId), None, None, false)
    var takenPickeeIds = pickeeRepo.takenPickeeIds(leagueId)
    if (takenPickeeIds.contains(internalPickeeId)){
      return Left(BadRequest("Another team already has that player"))
    }
    // TODO for yield this shit
    val validated = validateLimits(currentTeam.map(_.internalPickeeId).toSet + internalPickeeId, leagueId)
    if (validated.isRight) {
      generateCard(leagueId, userId, internalPickeeId, "FUKINWOTM8")
      takenPickeeIds = takenPickeeIds + internalPickeeId
      pickeeRepo.removePickeeFromQueues(internalPickeeId)
      val drafts = if (nextDrafterIds.isEmpty) Map() else draftAutopickUsersRecursively(leagueId, nextDrafterIds.tail, takenPickeeIds)
      // The +1 is due to extra draft of original caller, not because of 1-based indexing
      setNewDraftDeadline(leagueId, drafts.size + 1)
      val users = userRepo.getUsers(List(userId) ++ drafts.keys.toList)
      val pickees = pickeeRepo.getPickees(List(internalPickeeId) ++ drafts.keys.toList)
      val out = users.zip(pickees).map({case (uid, pid) => UserPickee(uid, pid)})
      Right(out)
    } else Left(validated.left.get)
  }

  private def draftAutopickUsersRecursively(leagueId: Long, userIds: Iterable[Long], takenPickeeIds: Set[Long])
                                       (implicit c: Connection): Map[Long, Long] = {
    if (userIds.isEmpty) return Map()
    val userId = userIds.head
    val queue ~ autopick = SQL"select pickee_ids, autopick from draft_queue where user_id = $userId".
      as((SqlParser.array[Long]("pickee_ids") ~ SqlParser.bool("autopick")).single)
    logger.info(s"Recursive pick. Autopick: $autopick\nDraft queue: ${queue.mkString}")
    logger.info(s"takenPickeeIds: ${takenPickeeIds.mkString}")
    if (!autopick) return Map()
    val currentTeam = teamRepo.getUserCards(leagueId, Some(userId), None, None, false)
    val firstUntakenPickeeId = queue.find(
      pid => !takenPickeeIds.contains(pid) && validateLimits(currentTeam.map(_.internalPickeeId).toSet + pid, leagueId).isRight)
    if (firstUntakenPickeeId.isDefined) {
      generateCard(leagueId, userId, firstUntakenPickeeId.get, "FUKINWOTM8")
      pickeeRepo.removePickeeFromQueues(firstUntakenPickeeId.get)
      Map[Long, Long](userId -> firstUntakenPickeeId.get) ++
        (if (userIds.isEmpty) Map() else
          draftAutopickUsersRecursively(leagueId, userIds.tail, takenPickeeIds + firstUntakenPickeeId.get)
          )
    } else Map()
  }

  override def getDraftQueue(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[DraftQueueRow] = {
    // https://stackoverflow.com/a/23838131
    SQL"""
         select external_pickee_id, pickee_name, pickee_id from pickee
         join (select unnest(pickee_ids) as pickee_id, generate_subscripts(pickee_ids, 1) as queue_idx
         from draft_queue where user_id = $userId) sub using(pickee_id) order by queue_idx;
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
         select coalesce(array_length(user_ids, 1), 0) as countu from draft_order where league_id = $leagueId
      """.as(SqlParser.int("countu").single)
  }

  override def getDraftDeadlines()(implicit c: Connection): List[DraftSystem] = {
    val parser = Macro.namedParser[DraftSystem](ColumnNaming.SnakeCase)
    // get rid of nulls as theyre finished drafts
    // partial index on the null?
    SQL"""select league_id, next_draft_deadline from draft_system
          where next_draft_deadline is not null and not manual_draft and not paused and not completed""".as(parser.*)
  }

  override def draftDeadlineReached(leagueId: Long)(implicit c: Connection): Option[LocalDateTime] = {
    val draftInfo = getDraftInfo(leagueId)
    // Someone made a draft, or turned autopick on, rather than timer elapsing
    // If draft paused we dont want to take any action either
    logger.debug(s"draftInfo.numMissed: ${draftInfo.missed}")
    if (!draftInfo.missed || draftInfo.paused){
      Some(draftInfo.nextDraftDeadline)
    }
    else{
      val queuePickeeIds = SQL"""
        select user_id, queued_pickee_id from (select unnest(pickee_ids) as queued_pickee_id, user_id from draft_queue
          join (select user_ids[1] as user_id from draft_order where league_id = $leagueId) next_drafter using(user_id)
        ) as pickee_queue
        where not exists(
          select p.pickee_id from pickee p join card using(pickee_id) where league_id = $leagueId and pickee_id = queued_pickee_id
        )
        """.as((SqlParser.long("user_id") ~ SqlParser.long("queued_pickee_id")).*)
      logger.info(s"queuePickeeIds: ${queuePickeeIds.mkString}")
      if (queuePickeeIds.nonEmpty){
        val userId = queuePickeeIds.head._1
        val currentTeam = teamRepo.getUserCards(leagueId, Some(userId), None, None, false)
        val pick = queuePickeeIds.find({
          case _ ~ pid => validateLimits(currentTeam.map(_.internalPickeeId).toSet + pid, leagueId).isRight
        })
        logger.info(s"draft pick: $pick")
        pick.map({
          case _ ~ pid => generateCard(leagueId, userId, pid, "FUKINWOTM8")
        })
      }
      val nextDrafterIds: List[Long] = SQL"select unnest(user_ids) as user_id from draft_order where league_id = $leagueId"
        .as(SqlParser.long("user_id").*).tail
        val takenPickeeIds = SQL"select pickee_id from pickee join card using(pickee_id) where league_id = $leagueId".
          as(SqlParser.long("pickee_id").*).toSet
        val drafts = if (nextDrafterIds.isEmpty) Map() else draftAutopickUsersRecursively(leagueId, nextDrafterIds.tail, takenPickeeIds)
        setNewDraftDeadline(leagueId, drafts.size+1)
    }
  }

  private def sliceDrafts(leagueId: Long, numSkips: Int)(implicit c: Connection): Boolean = {
    // TODO this can skip over autopick users if one before is not autopick
    // have limited this to max 1 in caller.
    if (numSkips > 0){
      val usersLeft = SQL"""
            update draft_order set user_ids = user_ids[${numSkips+1}:]
             where league_id = $leagueId
             returning coalesce(array_length(user_ids, 1), 0) as left""".as(SqlParser.int("left").singleOpt)
      usersLeft match{
        // Pause over completion because commissioner can un-pause and retry draft if something went wrong
        case Some(x) if x == 0 => SQL"""update draft_system set paused = true where league_id = $leagueId""".executeUpdate()
        case _ => logger.debug(s"usersLeft: $usersLeft")
      }
      usersLeft.isDefined
    } else false
  }

  private def getDraftInfo(leagueId: Long)(implicit c: Connection): DraftInfo = {
    // FInd out since the last call how many users must have missed their go
    val parser: RowParser[DraftInfo] = Macro.namedParser[DraftInfo](ColumnNaming.SnakeCase)
    logger.debug("\n\n\n\n\n\n\ntime now: ")
    logger.debug(LocalDateTime.now.toString)
    val out = SQL"""
         select now() > next_draft_deadline as missed, draft_start > now() as unstarted,
         draft_start, next_draft_deadline, paused
         from draft_system where league_id = $leagueId
      """.as(parser.single)
    logger.debug(s"nextDraftDeadline was: ${out.nextDraftDeadline.toString}")
    out
  }

  private def setNewDraftDeadline(leagueId: Long, numDrafted: Int)(implicit c: Connection): Option[LocalDateTime] = {
    val updated = sliceDrafts(leagueId, numDrafted)
    // Update deadline for next user in list
    if (updated) {
      Some(SQL"""update draft_system
         set next_draft_deadline = now() + interval '1 second' * choice_timer
         where league_id = $leagueId returning next_draft_deadline as datetime""".as(Macro.namedParser[DatetimeRow].single).datetime)
    } else None
  }

  override def manualDraft(leagueId: Long, input: ManualDraftFormInput)(implicit  c: Connection):
  Either[Result, Iterable[CardOut]] = {
    val allPickees = pickeeRepo.getAllPickees(leagueId)
    Right(input.teams.flatMap(t => t.pickees.map(p => {
      val internalPickee = allPickees.find(p2 => p2.pickeeName == p)
      if (internalPickee.isEmpty){
        return Left(BadRequest(s"Pickee could not be found: $p"))
      } else {
        val internalPickeeId = internalPickee.get.internalPickeeId
        generateCard(leagueId, t.userId, internalPickeeId, "FUKINWOTM8")
      }
    })))
  }

  override def setDraftPaused(leagueId: Long, paused: Boolean)(implicit  c: Connection) = {
    SQL"""update draft_system set paused = $paused where league_id = $leagueId""".executeUpdate()
    if (!paused){
      // If unpausing we need to reset deadline, as it will still have been ticking towards it when paused
      // (even though no actions will have been taken)
      SQL"""update draft_system set next_draft_deadline = now() + interval '1 second' * choice_timer where league_id = $leagueId""".executeUpdate()
    }
  }

  override def processWaiverPickees(leagueId: Long, teamSize: Int, periodVal: Int)(implicit c: Connection): Unit = {
    // i dont like map and toSet
    var usersToFill = userRepo.getAllUsersForLeague(leagueId, Some(false)).map(_.userId).toSet
    logger.info(s"usersToFill: ${usersToFill.mkString(",")}")
    while (usersToFill.nonEmpty) {
      logger.debug(s"usersToFill now: ${usersToFill.mkString(",")}")
      // Have to be careful.
      // As the top priority might have full team, we dont want to knock them down order without giving them a pick
      // Therefore record index of first unpicked, so we can just nudge them to the back
      val userId ~ indx =
        SQL"""
             select * from (select unnest(user_ids) as user_id, generate_subscripts(user_ids, 1) as queue_idx
             from waiver_order
             where league_id = $leagueId) sub where user_id IN ($usersToFill)
             order by queue_idx limit 1
          """.as((SqlParser.long("user_id") ~ SqlParser.int("queue_idx")).single)
//        SQL"""
//             select user_ids[1] as user_id from waiver_order where league_id = $leagueId and user_id = ANY($usersToFill)
//          """.as(SqlParser.long("user_id").single)
      logger.debug(s"userId: $userId")
      val queue = getDraftQueue(leagueId, userId)
      val currentTeam = teamRepo.getUserCards(leagueId, Some(userId), None, None, false)
      val takenPickeeIds = pickeeRepo.takenPickeeIds(leagueId, periodVal)
      val firstUntakenPickeeId = queue.find(
        q => !takenPickeeIds.contains(q.pickeeId) && validateLimits(currentTeam.map(_.internalPickeeId).toSet + q.pickeeId, leagueId).isRight)
      logger.debug(s"firstUntakenPickeeId: $firstUntakenPickeeId")
      logger.debug(s"currentTeam.size: ${currentTeam.size}. teamSize: $teamSize")
      if (firstUntakenPickeeId.isDefined) {
        generateCard(leagueId, userId, firstUntakenPickeeId.get.pickeeId, "FUKINWOTM8")
        pickeeRepo.removePickeeFromQueues(firstUntakenPickeeId.get.pickeeId)

        // Nudge user getting a new pickee to back of waiver_order list
        SQL"""
             update waiver_order set user_ids = user_ids[:$indx-1] || user_ids[$indx+1:] || user_ids[$indx] where league_id = $leagueId
          """.executeUpdate()
      }
      else if (currentTeam.size < teamSize) {
        // Give them a random valid untaken pickee
        val untakenPickees: Set[Long] = pickeeRepo.getAllPickees(leagueId).map(_.internalPickeeId).toSet -- takenPickeeIds
        if (untakenPickees.isEmpty) return
        val untakenPickeesArr = untakenPickees.toArray[Long]
        val randomPickeeId: Long = untakenPickeesArr(Random.nextInt(takenPickeeIds.size))
        generateCard(leagueId, userId, randomPickeeId, "FUKINWOTM8")
      }
      else {
        usersToFill = usersToFill - userId
      }
    }
  }

}

