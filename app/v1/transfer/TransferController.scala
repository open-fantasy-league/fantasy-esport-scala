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
import v1.leagueuser.LeagueUserRepo
import v1.team.TeamRepo
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo

case class TransferFormInput(buy: List[Long], sell: List[Long], isCheck: Boolean, wildcard: Boolean)

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
                                    leagueUserRepo: LeagueUserRepo, teamRepo: TeamRepo, pickeeRepo: PickeeRepo)
                                  (implicit ec: ExecutionContext, leagueRepo: LeagueRepo, db: Database) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val transferForm: Form[TransferFormInput] = {

    Form(
    mapping(
    "buy" -> default(list(of(longFormat)), List()),
    "sell" -> default(list(of(longFormat)), List()),
    "isCheck" -> boolean,
    "wildcard" -> default(boolean, false)
    //  "delaySeconds" -> optional(number)
    )(TransferFormInput.apply)(TransferFormInput.unapply)
    )
  }
  implicit val parser = parse.default

  // todo add a transfer check call
  def scheduleTransferReq(userId: String, leagueId: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new LeagueUserAction(leagueUserRepo, db)(userId).auth(Some(leagueUserRepo.joinUsers))).async { implicit request =>
    scheduleTransfer(request.league, request.leagueUser)
  }

  def processTransfersReq(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction).async { implicit request =>
    Future {
      val currentTime = LocalDateTime.now()
      db.withConnection { implicit c =>
        val updates = leagueUserRepo.getShouldProcessTransfer(request.league.leagueId).map(transferRepo.processLeagueUserTransfer)
      }
      Ok("Transfer updates processed")
    }
  }

  def getUserTransfersReq(userId: String, leagueId: String) = (new LeagueAction(leagueId) andThen
    new LeagueUserAction(leagueUserRepo, db)(userId).apply()).async { implicit request =>
    Future{
      db.withConnection { implicit c =>
        val processed = request.getQueryString("processed").map(_ (0) == 't')
        Ok(Json.toJson(transferRepo.getLeagueUserTransfer(request.leagueUser.leagueUserId, processed)))
      }
    }
  }

  private def scheduleTransfer[A](league: LeagueRow, leagueUser: LeagueUserRow)(implicit request: Request[A]): Future[Result] = {
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
          (for {
            _ <- validateDuplicates(input.sell, sell, input.buy, buy)
            leagueStarted = leagueRepo.isStarted(league)
            validateTransferOpen <- if (league.transferOpen) Right(true) else Left(BadRequest("Transfers not currently open for this league"))
            applyWildcard <- shouldApplyWildcard(input.wildcard, league.transferWildcard, leagueUser.usedWildcard, sell)
            newRemaining <- updatedRemainingTransfers(leagueStarted, leagueUser.remainingTransfers, sell)
            pickees = pickeeRepo.getPickees(league.leagueId).toList
            newMoney <- updatedMoney(leagueUser.money, pickees, sell, buy, applyWildcard, league.startingMoney)
            currentTeamIds <- tryOrResponse(() => teamRepo.getLeagueUserTeam(leagueUser.leagueUserId).map(_.externalPickeeId).toSet
            , InternalServerError("Missing pickee externalPickeeId"))
            _ = println(s"currentTeamIds: ${currentTeamIds.mkString(",")}")
            sellOrWildcard = if (applyWildcard) currentTeamIds else sell
            _ = println(s"sellOrWildcard: ${sellOrWildcard.mkString(",")}")
            // use empty set as otherwis you cant rebuy heroes whilst applying wildcard
            _ <- validatePickeeIds(if (applyWildcard) Set() else currentTeamIds, pickees, sell, buy)
            newTeamIds = (currentTeamIds -- sellOrWildcard) ++ buy
            _ = println(s"newTeamIds: ${newTeamIds.mkString(",")}")
            _ <- updatedTeamSize(newTeamIds, league.teamSize, input.isCheck)
            _ <- validateLimits(newTeamIds, league.leagueId)
            transferDelay = if (!leagueStarted) None else Some(league.transferDelayMinutes)
            out <- if (input.isCheck) Right(Ok(Json.toJson(TransferSuccess(newMoney, newRemaining)))) else
              updateDBScheduleTransfer(
                sellOrWildcard, buy, pickees, leagueUser, leagueRepo.getCurrentPeriod(league).map(_.value).getOrElse(0), newMoney,
                newRemaining, transferDelay, applyWildcard)
          } yield out).fold(identity, identity)
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

  private def validatePickeeIds(
                                 currentTeamIds: Set[Long], pickees: Iterable[PickeeRow], toSell: Set[Long],
                                 toBuy: Set[Long]): Either[Result, Boolean] = {
    // TODO return what ids are invalid
    (toSell ++ toBuy).subsetOf(pickees.map(_.externalPickeeId).toSet) match {
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

  private def updatedTeamSize(newTeamIds: Set[Long], leagueTeamSize: Int, isCheck: Boolean): Either[Result, Int] = {
    newTeamIds.size match {
      case x if x <= leagueTeamSize => Right(x)
      //case x if x < league.teamSize && !isCheck => Left(BadRequest(f"Cannot confirm transfers as team unfilled (require ${league.teamSize})"))
      case x => Left(BadRequest(
        f"Exceeds maximum team size of $leagueTeamSize"
      ))
    }
  }

  private def validateLimits(newTeamIds: Set[Long], leagueId: Long)(implicit c: Connection): Either[Result, Any] = {
    // TODO errrm this is a bit messy
    transferRepo.pickeeLimitsValid(leagueId, newTeamIds) match {
        case true => Right(true)
        case false => Left(BadRequest(
          f"Exceeds limit limit"  // TODO what limit does it exceed
        ))
      }
  }

  private def updateDBScheduleTransfer(
                                toSell: Set[Long], toBuy: Set[Long], pickees: Iterable[PickeeRow], leagueUser: LeagueUserRow,
                                period: Int, newMoney: BigDecimal, newRemaining: Option[Int], transferDelay: Option[Int],
                                applyWildcard: Boolean
                              )(implicit c: Connection): Either[Result, Result] = {
    tryOrResponseRollback(() => {
      val currentTime = LocalDateTime.now()
      val scheduledUpdateTime = transferDelay.map(td => currentTime.plusMinutes(td))
      val toSellPickees = toSell.map(ts => pickees.find(_.externalPickeeId == ts).get)
      toSellPickees.map(
        p => transferRepo.insert(
          leagueUser.leagueUserId, p.internalPickeeId, false, currentTime, scheduledUpdateTime.getOrElse(currentTime),
          scheduledUpdateTime.isEmpty, p.price, applyWildcard
        )
      )
      val toBuyPickees = toBuy.map(tb => pickees.find(_.externalPickeeId == tb).get)
      toBuyPickees.map(
        p => transferRepo.insert(
          leagueUser.leagueUserId, p.internalPickeeId, true, currentTime, scheduledUpdateTime.getOrElse(currentTime),
          scheduledUpdateTime.isEmpty, p.price, applyWildcard
        ))
      val currentTeam = teamRepo.getLeagueUserTeam(leagueUser.leagueUserId).map({
        cp => pickees.find(_.externalPickeeId == cp.externalPickeeId).get.internalPickeeId
      }).toSet
      if (scheduledUpdateTime.isEmpty) {
        transferRepo.changeTeam(
          leagueUser.leagueUserId, toBuyPickees.map(_.internalPickeeId), toSellPickees.map(_.internalPickeeId), currentTeam, currentTime
        )
      }
      leagueUserRepo.update(
        leagueUser.leagueUserId, newMoney, newRemaining, scheduledUpdateTime, applyWildcard
      )
      Ok(Json.toJson(TransferSuccess(newMoney, newRemaining)))
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
