package v1.transfer

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.Inject
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import utils.TryHelper.{tryOrResponse, tryOrResponseRollback}
import models._
import play.api.db._
import auth._
import utils.IdParser
import v1.user.UserRepo
import v1.team.TeamRepo
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo

case class TransferFormInput(buy: List[Long], sell: List[Long], isCheck: Boolean, wildcard: Boolean, applyStart: Option[LocalDateTime],
                             applyEnd: Option[LocalDateTime], applyStartPeriod: Option[Int], applyEndPeriod: Option[Int])

case class TransferSuccess(updatedMoney: BigDecimal, remainingTransfers: Option[Int])

object TransferSuccess{
  implicit val implicitWrites = new Writes[TransferSuccess] {
    def writes(t: TransferSuccess): JsValue = {
      Json.obj(
        "updatedMoney" -> t.updatedMoney,
        "remainingTransfers" -> t.remainingTransfers
      )
    }
  }
}

class TransferController @Inject()(
                                    cc: ControllerComponents, Auther: Auther, transferRepo: TransferRepo,
                                    userRepo: UserRepo, teamRepo: TeamRepo, pickeeRepo: PickeeRepo)
                                  (implicit ec: ExecutionContext, leagueRepo: LeagueRepo, db: Database) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val transferForm: Form[TransferFormInput] = {

    Form(
    mapping(
    "buy" -> default(list(of(longFormat)), List()),
    "sell" -> default(list(of(longFormat)), List()),
    "isCheck" -> boolean,
    "wildcard" -> default(boolean, false),
      "applyStart" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm"))),
      "applyEnd" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm"))),
      "applyStartPeriod" -> optional(number),
      "applyEndPeriod" -> optional(number)
    //  "delaySeconds" -> optional(number)
    )(TransferFormInput.apply)(TransferFormInput.unapply)
    )
  }
  implicit val parser = parse.default

  // todo add a transfer check call
  def transferReq(userId: String, leagueId: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>
    makeTransfer(request.league, request.user)
  }

  def getUserTransfersReq(userId: String, leagueId: String) = (new LeagueAction(leagueId) andThen
    new UserAction(userRepo, db)(userId).apply()).async { implicit request =>
    Future{
      db.withConnection { implicit c =>
        val processed = request.getQueryString("processed").map(_ (0) == 't')
        Ok(Json.toJson(transferRepo.getUserTransfer(request.user.userId, processed)))
      }
    }
  }

  def generateCardPackReq(userId: String, leagueId: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>
    Future {
      db.withTransaction { implicit c =>
        tryOrResponseRollback({transferRepo.buyCardPack(
          request.league.leagueId, request.user.userId, request.league.packSize.get, request.league.packCost.get
        ).fold(
          l => BadRequest(l), r => Ok(Json.toJson(r.toList))
        )}, c, InternalServerError("Something went wrong buying card")).fold(identity, identity)
      }
    }
  }

  def recycleCardReq(userId: String, leagueId: String, cardId: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>
    Future {
      (for {
        cardIdLong <- IdParser.parseLongId(cardId, "card id")
        succeeded <- db.withTransaction { implicit c =>
          tryOrResponseRollback(transferRepo.recycleCard(
            request.league.leagueId, request.user.userId, cardIdLong, request.league.recycleValue.get
          ), c,
            InternalServerError("Something went wrong recycling card"))
        }
        out <- if(succeeded) Right(Ok(Json.toJson("success" -> true))) else Left(BadRequest(s"Card: $cardId does not exist or user: $userId does not own card"))
      } yield out).fold(identity, identity)
    }
  }

  private def makeTransfer[A](league: LeagueRow, user: UserRow)(implicit request: Request[A]): Future[Result] = {
    val isCard = league.isCardSystem
    def failure(badForm: Form[TransferFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: TransferFormInput): Future[Result] = {
      val sell = input.sell.toSet
      val buy = input.buy.toSet
      if (sell.isEmpty && buy.isEmpty && !input.wildcard && !input.isCheck){
        return Future.successful(BadRequest("Attempted to confirm transfers, however no changes planned"))
      }

      Future {
        db.withTransaction { implicit c =>
          if (isCard){
            (for {
              // TODO does select for update lock/block other reads?
              _ <- validateDuplicates(input.sell, sell, input.buy, buy)
              // TODO what about last week of season
              currentPeriod = leagueRepo.getCurrentPeriod(league)
              userIsLateStart = currentPeriod.isDefined && user.entered.isAfter(currentPeriod.get.start)
              defaultPeriod <- if (userIsLateStart)
                currentPeriod.toRight(InternalServerError("Couldnt find current period")) else
                leagueRepo.getNextPeriod(league)
              periodStart = input.applyStartPeriod.getOrElse(defaultPeriod.value)
              periodEnd = input.applyEndPeriod
              _ <- validateNewUserCantChangeDuringPeriod(userIsLateStart, user.lateEntryLockTs, input.isCheck)
              userCards = pickeeRepo.getUserCards(league.leagueId, user.userId).toList
              currentTeamIds <- tryOrResponse(
                teamRepo.getUserTeamForPeriod(user.userId, periodStart, periodEnd).map(_.cardId).toSet
                , InternalServerError("Missing pickee externalPickeeId"))
              _ = println(s"currentTeamIds: ${currentTeamIds.mkString(",")}")
              _ = println(s"sellOrWildcard: ${sell.mkString(",")}")
              _ <- validateIds(currentTeamIds, userCards.map(_.cardId).toSet, sell, buy)
              newTeamCardIds = (currentTeamIds -- sell) ++ buy
              newTeamPickeeIdsList = userCards.withFilter(c => newTeamCardIds.contains(c.cardId)).map(_.pickeeId)
              _ = println(s"newTeamCardIds: ${newTeamCardIds.mkString(",")}")
              newTeamPickeeIdsSet <- validateUniquePickees(newTeamPickeeIdsList)
              _ <- updatedTeamSize(newTeamPickeeIdsSet, league.teamSize, input.isCheck, league.forceFullTeams)
              _ <- validateLimits(newTeamPickeeIdsSet, league.leagueId)
              out <- if (input.isCheck) Right(Ok(Json.toJson(TransferSuccess(user.money, None)))) else
                updateDBCardTransfer(
                  sell, buy, currentTeamIds, user, periodStart, periodEnd, userIsLateStart
                )
            } yield out).fold(identity, identity)
          } else{
          (for {
            // TODO does select for update lock/block other reads?
            _ <- validateDuplicates(input.sell, sell, input.buy, buy)
            leagueStarted = leagueRepo.isStarted(league)
            _ <- if (league.transferOpen) Right(true) else Left(BadRequest("Transfers not currently open for this league"))
            currentPeriod = leagueRepo.getCurrentPeriod(league)
            userIsLateStart = currentPeriod.isDefined && user.entered.isAfter(currentPeriod.get.start)
            defaultPeriod <- if (userIsLateStart)
              currentPeriod.toRight(InternalServerError("Couldnt find current period")) else
              leagueRepo.getNextPeriod(league)
            periodStart = input.applyStartPeriod.getOrElse(defaultPeriod.value)
            periodEnd = input.applyEndPeriod
            _ <- validateNewUserCantChangeDuringPeriod(userIsLateStart, user.lateEntryLockTs, input.isCheck)
            applyWildcard <- shouldApplyWildcard(input.wildcard, league.transferWildcard.get, user.usedWildcard, sell)
            newRemaining <- updatedRemainingTransfers(leagueStarted, user.remainingTransfers, sell)
            pickees = pickeeRepo.getPickees(league.leagueId).toList
            newMoney <- updatedMoney(user.money, pickees, sell, buy, applyWildcard, league.startingMoney)
            currentTeamIds <- tryOrResponse(teamRepo.getUserTeam(user.userId).map(_.externalPickeeId).toSet
            , InternalServerError("Missing pickee externalPickeeId"))
            _ = println(s"currentTeamIds: ${currentTeamIds.mkString(",")}")
            sellOrWildcard = if (applyWildcard) currentTeamIds else sell
            _ = println(s"sellOrWildcard: ${sellOrWildcard.mkString(",")}")
            // use empty set as otherwis you cant rebuy heroes whilst applying wildcard
            _ <- validateIds(if (applyWildcard) Set() else currentTeamIds, pickees.map(_.externalPickeeId).toSet, sell, buy)
            newTeamIds = (currentTeamIds -- sellOrWildcard) ++ buy
            _ = println(s"newTeamIds: ${newTeamIds.mkString(",")}")
            _ <- updatedTeamSize(newTeamIds, league.teamSize, input.isCheck, league.forceFullTeams)
            _ <- validateLimits(newTeamIds, league.leagueId)
            out <- if (input.isCheck) Right(Ok(Json.toJson(TransferSuccess(newMoney, newRemaining)))) else
              updateDBTransfer(
                league.leagueId, sellOrWildcard, buy, pickees, user,
                leagueRepo.getCurrentPeriod(league).map(_.value).getOrElse(0), newMoney,
                newRemaining, applyWildcard, periodStart, periodEnd)
          } yield out).fold(identity, identity) }
        }
      }
    }

    transferForm.bindFromRequest().fold(failure, success)
  }

  private def validateDuplicates(sellList: List[Long], sellSet: Set[Long], buyList: List[Long], buySet: Set[Long]): Either[Result, Any] = {
    if (buyList.size != buySet.size) return Left(BadRequest("Cannot buy twice"))
    if (sellList.size != sellSet.size) return Left(BadRequest("Cannot sell twice"))
    Right(true)
  }

  private def updatedRemainingTransfers(leagueStarted: Boolean, remainingTransfers: Option[Int], toSell: Set[Long]): Either[Result, Option[Int]] = {
    if (!leagueStarted){
      return Right(remainingTransfers)
    }
    val newRemaining = remainingTransfers.map(_ - toSell.size)
    newRemaining match{
      case Some(x) if x < 0 => Left(BadRequest(
        f"Insufficient remaining transfers: $remainingTransfers"
      ))
      case Some(x) => Right(Some(x))
      case None => Right(None)
    }
  }

  private def validateIds(
                                 currentTeamIds: Set[Long], availableIds: Set[Long], toSell: Set[Long],
                                 toBuy: Set[Long]): Either[Result, Boolean] = {
    // TODO return what ids are invalid
    (toSell ++ toBuy).subsetOf(availableIds) match {
      case true => {
        toBuy.intersect(currentTeamIds).isEmpty match {
          case true => {
            toSell.subsetOf(currentTeamIds) match {
              case true => Right(true)
              case false => Left(BadRequest("Cannot sell hero not in team"))
            }
          }
          case false => Left(BadRequest("Cannot buy hero already in team"))
        }

      }   case false => Left(BadRequest("Invalid pickee id used"))
    }
  }

  private def updatedMoney(
                            money: BigDecimal, pickees: Iterable[PickeeRow], toSell: Set[Long], toBuy: Set[Long],
                            wildcardApplied: Boolean, startingMoney: BigDecimal): Either[Result, BigDecimal] = {
    val spent = pickees.filter(p => toBuy.contains(p.externalPickeeId)).map(_.price).sum
    println(spent)
    println(toBuy)
    val updated = wildcardApplied match {
      case false => money + pickees.filter(p => toSell.contains(p.externalPickeeId)).map(_.price).sum - spent
      case true => startingMoney - spent
    }
    updated match {
      case x if x >= 0 => Right(x)
      case x => Left(BadRequest(
        f"Insufficient credits. Transfers would leave user at $x credits"
      ))
    }
  }

  private def updatedTeamSize(newTeamIds: Set[Long], leagueTeamSize: Int, isCheck: Boolean, forceFullTeams: Boolean): Either[Result, Int] = {
    newTeamIds.size match {
      case x if x <= leagueTeamSize => Right(x)
      case x if x < leagueTeamSize && !isCheck && forceFullTeams => Left(BadRequest(f"Cannot confirm transfers as team unfilled (require $leagueTeamSize)"))
      case x => Left(BadRequest(
        f"Exceeds maximum team size of $leagueTeamSize"
      ))
    }
  }

  private def validateLimits(newTeamIds: Set[Long], leagueId: Long)(implicit c: Connection): Either[Result, Any] = {
    // TODO errrm this is a bit messy
    transferRepo.pickeeLimitsInvalid(leagueId, newTeamIds) match {
        case None => Right(true)
        case Some((name, max_)) => Left(BadRequest(
          f"Exceeds $name limit: max $max_ allowed"  // TODO what limit does it exceed
        ))
      }
  }

  private def validateUniquePickees(newTeamIds: List[Long]): Either [Result, Set[Long]] = {
    val setIds = newTeamIds.toSet
    if (newTeamIds.size != setIds.size) Left(BadRequest("Cannot have two identical players in team"))
    else Right(setIds)
  }

  private def validateNewUserCantChangeDuringPeriod(
                                                     userIsLateStart: Boolean, lateEntryLockTs: Option[LocalDateTime],
                                                     isCheck: Boolean
                                                   ): Either[Result, Any] = {
    // TODO but new user should be able to pick team for next period
    if (userIsLateStart && lateEntryLockTs.isDefined) Left(BadRequest("Have already locked team for this period"))
    else Right(true)
  }

  private def updateDBTransfer(
                                leagueId: Long, toSell: Set[Long], toBuy: Set[Long], pickees: Iterable[PickeeRow], user: UserRow,
                                period: Int, newMoney: BigDecimal, newRemaining: Option[Int],
                                applyWildcard: Boolean, periodStart: Int, periodEnd: Option[Int]
                              )(implicit c: Connection): Either[Result, Result] = {
    tryOrResponseRollback({
      val currentTime = LocalDateTime.now()
      val toSellPickees = toSell.map(ts => pickees.find(_.externalPickeeId == ts).get)
      toSellPickees.map(
        p => transferRepo.insert(
          user.userId, p.internalPickeeId, false, currentTime, periodStart, periodEnd, p.price, applyWildcard
        )
      )
      val toBuyPickees = toBuy.map(tb => pickees.find(_.externalPickeeId == tb).get)
      toBuyPickees.map(
        p => transferRepo.insert(
          user.userId, p.internalPickeeId, true, currentTime, periodStart, periodEnd, p.price, applyWildcard
        ))
      val ct = teamRepo.getUserTeam(user.userId)
      val currentTeam = ct.map(_.cardId).toSet
      val toBuyCardIds = toBuyPickees.map(b => transferRepo.generateCard(leagueId, user.userId, b.internalPickeeId, "").cardId)
      val toSellCardIds = toSell.map(ts => ct.find(c => ts == c.externalPickeeId).get).map(_.cardId)
      transferRepo.changeTeam(
        user.userId, toBuyCardIds, toSellCardIds, currentTeam, periodStart, periodEnd
      )
      userRepo.updateFromTransfer(
        user.userId, newMoney, newRemaining, applyWildcard
      )
      Ok(Json.toJson(TransferSuccess(newMoney, newRemaining)))
    }, c, InternalServerError("Unexpected error whilst processing transfer")
    )
  }

  private def updateDBCardTransfer(
                                        toSell: Set[Long], toBuy: Set[Long], currentTeamIds: Set[Long], user: UserRow,
                                        periodStart: Int, periodEnd: Option[Int], userIsLateStart: Boolean
                                      )(implicit c: Connection): Either[Result, Result] = {
    tryOrResponseRollback({
        transferRepo.changeTeam(
          user.userId, toBuy, toSell, currentTeamIds, periodStart, periodEnd
        )
      if (userIsLateStart) userRepo.setlateEntryLockTs(user.userId)
      val newMoney = 10.0 // TODO actual new credits
      Ok(Json.toJson(TransferSuccess(newMoney, None)))
    }, c, InternalServerError("Unexpected error whilst processing transfer")
    )
  }

  private def shouldApplyWildcard(attemptingWildcard: Boolean, leagueHasWildcard: Boolean, usedWildcard: Boolean, toSell: Set[Long]): Either[Result, Boolean] = {
    if (toSell.nonEmpty && attemptingWildcard) return Left(BadRequest("Cannot sell heroes AND use wildcard at same time"))
    if (!attemptingWildcard) return Right(false)
    leagueHasWildcard match {
      case true => usedWildcard match {
        case true => Left(BadRequest("User already used up wildcard"))
        case _ => Right(true)
      }
      case _ => Left(BadRequest(f"League does not have wildcards"))
    }
  }
}
