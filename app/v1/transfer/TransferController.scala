package v1.transfer

import java.sql.Connection
import java.time.LocalDateTime
import javax.inject.Inject
import java.util.concurrent.TimeUnit

import entry.SquerylEntrypointForMyApp._
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.immutable.{List, Set}
import scala.util.Try
import models._
import models.AppDB._
import play.api.db._
import auth._
import v1.leagueuser.LeagueUserRepo
import v1.team.TeamRepo

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
                                    db: Database, cc: ControllerComponents, Auther: Auther, transferRepo: TransferRepo,
                                    leagueUserRepo: LeagueUserRepo, teamRepo: TeamRepo)(implicit ec: ExecutionContext) extends AbstractController(cc)
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
    db.withConnection { implicit c =>
    new LeagueUserAction(userId).auth(Some(leagueUserRepo.joinUsers2))}).async { implicit request =>
    scheduleTransfer(request.league, request.leagueUser)
  }

  def processTransfersReq(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction).async { implicit request =>
    Future {
      inTransaction {
        val currentTime = LocalDateTime.now()
        // TODO better way? hard with squeryls weird dsl
        db.withConnection { implicit c =>
          val updates = request.league.users.associations.where(lu => lu.changeTstamp.isNotNull and lu.changeTstamp <= currentTime)
            .map(transferRepo.processLeagueUserTransfer)
        }
        Ok("Transfer updates processed")
      }
    }
  }

  def getUserTransfersReq(userId: String, leagueId: String) = (new LeagueAction(leagueId) andThen (new LeagueUserAction(userId)).apply()).async { implicit request =>
    Future{
      inTransaction{
        val unprocessed = request.getQueryString("unprocessed").map(s => false)
        Ok(Json.toJson(transferRepo.getLeagueUserTransfer(request.leagueUser, unprocessed)))
      }
    }
  }

  private def scheduleTransfer[A](league: League, leagueUser: LeagueUser)(implicit request: Request[A]): Future[Result] = {
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
        inTransaction {
          (for {
            _ <- validateDuplicates(input.sell, sell, input.buy, buy)
            validateTransferOpen <- if (league.transferOpen) Right(true) else Left(BadRequest("Transfers not currently open for this league"))
            applyWildcard <- shouldApplyWildcard(input.wildcard, league, leagueUser, sell)
            newRemaining <- updatedRemainingTransfers(league, leagueUser, sell)
            pickees = from(league.pickees)(select(_)).toList
            newMoney <- updatedMoney(leagueUser, pickees, sell, buy, applyWildcard, league.startingMoney)
            currentTeamIds <- {db.withConnection{ implicit c => Try(teamRepo.getLeagueUserTeam(leagueUser).map(_.pickeeId).toSet
            ).toOption.toRight(InternalServerError("Missing pickee externalId"))}}
            _ = println(s"currentTeamIds: ${currentTeamIds.mkString(",")}")
            sellOrWildcard = if (applyWildcard) currentTeamIds else sell
            _ = println(s"sellOrWildcard: ${sellOrWildcard.mkString(",")}")
            // use empty set as otherwis you cant rebuy heroes whilst applying wildcard
            _ <- validatePickeeIds(if (applyWildcard) Set() else currentTeamIds, pickees, sell, buy)
            newTeamIds = (currentTeamIds -- sellOrWildcard) ++ buy
            _ = println(s"newTeamIds: ${newTeamIds.mkString(",")}")
            _ <- updatedTeamSize(newTeamIds, league, input.isCheck)
            _ <- validateLimitLimit(newTeamIds, league)
            transferDelay = if (!league.started) None else Some(league.transferDelayMinutes)
            out <- if (input.isCheck) Right(Ok(Json.toJson(TransferSuccess(newMoney, newRemaining)))) else
              updateDBScheduleTransfer(
                sellOrWildcard, buy, pickees, leagueUser, league.currentPeriod.getOrElse(new Period()).value, newMoney,
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

  private def updatedRemainingTransfers(league: League, leagueUser: LeagueUser, toSell: Set[Long]): Either[Result, Option[Int]] = {
    if (!league.started){
      return Right(leagueUser.remainingTransfers)
    }
    val newRemaining = leagueUser.remainingTransfers.map(_ - toSell.size)
    newRemaining match{
      case Some(x) if x < 0 => Left(BadRequest(
        f"Insufficient remaining transfers: $leagueUser.remainingTransfers"
      ))
      case Some(x) => Right(Some(x))
      case None => Right(None)
    }
  }

  private def validatePickeeIds(currentTeamIds: Set[Long], pickees: Iterable[Pickee], toSell: Set[Long], toBuy: Set[Long]): Either[Result, Boolean] = {
    // TODO return what ids are invalid
    (toSell ++ toBuy).subsetOf(pickees.map(_.externalId).toSet) match {
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
                            leagueUser: LeagueUser, pickees: Iterable[Pickee], toSell: Set[Long], toBuy: Set[Long],
                            wildcardApplied: Boolean, startingMoney: BigDecimal): Either[Result, BigDecimal] = {
    val spent = pickees.filter(p => toBuy.contains(p.externalId)).map(_.cost).sum
    println(spent)
    println(toBuy)
    val updated = wildcardApplied match {
      case false => leagueUser.money + pickees.filter(p => toSell.contains(p.externalId)).map(_.cost).sum - spent
      case true => startingMoney - spent
    }
    updated match {
      case x if x >= 0 => Right(x)
      case x => Left(BadRequest(
        f"Insufficient credits. Transfers would leave user at $x credits"
      ))
    }
  }

  private def updatedTeamSize(newTeamIds: Set[Long], league: League, isCheck: Boolean): Either[Result, Int] = {
    newTeamIds.size match {
      case x if x <= league.teamSize => Right(x)
      //case x if x < league.teamSize && !isCheck => Left(BadRequest(f"Cannot confirm transfers as team unfilled (require ${league.teamSize})"))
      case x => Left(BadRequest(
        f"Exceeds maximum team size of ${league.teamSize}"
      ))
    }
  }

  private def validateLimitLimit(newTeamIds: Set[Long], league: League): Either[Result, Any] = {
    //Right("cat")
    // TODO errrm this is a bit messy
    league.limitTypes.forall(limitType => {
      league.pickees.filter(lp => newTeamIds.contains(lp.externalId)).flatMap(_.limits).groupBy(_.limitTypeId)
        .forall({case (k, v) => v.size <= v.head.max})
    }) match {
        case true => Right(true)
        case false => Left(BadRequest(
          f"Exceeds limit limit"
        ))
      }
  }

  private def updateDBScheduleTransfer(
                                toSell: Set[Long], toBuy: Set[Long], pickees: Iterable[Pickee], leagueUser: LeagueUser,
                                period: Int, newMoney: BigDecimal, newRemaining: Option[Int], transferDelay: Option[Int],
                                applyWildcard: Boolean
                              ): Either[Result, Result] = {
    // TODO timedeltas
    val currentTime = LocalDateTime.now()
    val scheduledUpdateTime = transferDelay.map(td => currentTime + TimeUnit.MINUTES.toMillis(td))
    val toSellPickees = toSell.map(ts => pickees.find(_.externalId == ts).get)
    transferTable.insert(toSellPickees.map(
      p => new Transfer(
        leagueUser.id, p.id, false, currentTime, scheduledUpdateTime.getOrElse(currentTime),
          scheduledUpdateTime.isEmpty, p.cost, applyWildcard
        )
    ))
    val toBuyPickees = toBuy.map(tb => pickees.find(_.externalId == tb).get)
    transferTable.insert(toBuyPickees.map(
      p => new Transfer(leagueUser.id, p.id, true, currentTime, scheduledUpdateTime.getOrElse(currentTime),
        scheduledUpdateTime.isEmpty, p.cost)
    ))
    db.withConnection { implicit c =>
      val currentTeam = teamRepo.getLeagueUserTeam(leagueUser).map(cp => pickees.find(_.externalId == cp.pickeeId).get.id).toSet
      if (scheduledUpdateTime.isEmpty) {
        transferRepo.changeTeam(
          leagueUser, toBuyPickees.map(_.id), toSellPickees.map(_.id), currentTeam, currentTime
        )
      }
    }
    leagueUser.money = newMoney
    leagueUser.remainingTransfers = newRemaining
    leagueUser.changeTstamp = scheduledUpdateTime
    if (applyWildcard) leagueUser.usedWildcard = true
    leagueUserTable.update(leagueUser)
    Right(Ok(Json.toJson(TransferSuccess(newMoney, newRemaining))))
  }

  private def shouldApplyWildcard(attemptingWildcard: Boolean, league: League, leagueUser: LeagueUser, toSell: Set[Long]): Either[Result, Boolean] = {
    if (!toSell.isEmpty && attemptingWildcard) return Left(BadRequest("Cannot sell heroes AND use wildcard at same time"))
    if (!attemptingWildcard) return Right(false)
    league.transferWildcard match {
      case true => leagueUser.usedWildcard match {
        case true => Left(BadRequest("User already used up wildcard"))
        case _ => Right(true)
      }
      case _ => Left(BadRequest(f"League does not have wildcards"))
    }
  }
}
