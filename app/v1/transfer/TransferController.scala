package v1.transfer

import java.sql.Timestamp
import javax.inject.Inject
import java.util.concurrent.TimeUnit

import entry.SquerylEntrypointForMyApp._
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.immutable.{List, Set}
import scala.util.Try
import models._
import utils.{IdParser, CostConverter}

case class TransferFormInput(buy: List[Int], sell: List[Int], isCheck: Boolean, wildcard: Boolean)

class TransferController @Inject()(cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val transferForm: Form[TransferFormInput] = {

    Form(
    mapping(
    "buy" -> default(list(number), List()),
    "sell" -> default(list(number), List()),
    "isCheck" -> boolean,
    "wildcard" -> default(boolean, false)
    //  "delaySeconds" -> optional(number)
    )(TransferFormInput.apply)(TransferFormInput.unapply)
    )
  }

  // todo add a transfer check call
  def scheduleTransferReq(userId: String, leagueId: String) = Action.async(parse.json) { implicit request =>
    scheduleTransfer(userId, leagueId)
  }

  def processTransfersReq(leagueId: String) = Action.async(parse.json) { implicit request =>
    Future {
      inTransaction {
        //org.squeryl.Session.currentSession.setLogger(String => Unit)
        (for {
          leagueId <- IdParser.parseIntId(leagueId, "League")
          league <- AppDB.leagueTable.lookup(leagueId).toRight(BadRequest(f"League does not exist: $leagueId"))
          currentTime = new Timestamp(System.currentTimeMillis())
          // TODO better way? hard with squeryls weird dsl
          updates = league.users.associations.where(lu => lu.changeTstamp.isNotNull and lu.changeTstamp <= currentTime)
            .map(processLeagueUserTransfer)
//          updates = league.users.associations
//            .map(processLeagueUserTransfer)
          finished <- Right(Ok("Transfer updates processed"))
        } yield finished).fold(identity, identity)
      }
    }
  }

  private def scheduleTransfer[A](userId: String, leagueId: String)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[TransferFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: TransferFormInput) = {
      println("yay")
      // verify leagueUser exists
      // verify doesnt violate remaining transfers
      // verify can afford change
      // verify doesnt break team size lim
      // verify doesnt break faction limit

      // stop people from buying two of same hero at once
      // still need further check that hero not already in team
      val sell = input.sell.toSet
      val buy = input.buy.toSet

      Future {
        inTransaction {
          // TODO handle invalid Id
          (for {
            userId <- IdParser.parseIntId(userId, "User")
            leagueId <- IdParser.parseIntId(leagueId, "League")
            league <- AppDB.leagueTable.lookup(leagueId).toRight(BadRequest(f"League does not exist: $leagueId"))
          // TODO what does single return if no entries?
            leagueUser <- Try(league.users.associations.where(lu => lu.id === userId).single).toOption.toRight(BadRequest(f"User($userId) not in this league($leagueId)"))
            validateTransferOpen <- if (league.transferOpen) Right(true) else Left(BadRequest("Transfers not currently open for this league"))
            applyWildcard <- shouldApplyWildcard(input.wildcard, league, leagueUser, sell)
            newRemaining <- updatedRemainingTransfers(leagueUser, sell)
            pickees = from(league.pickees)(select(_)).toList
            newMoney <- updatedMoney(leagueUser, pickees, sell, buy, applyWildcard, league.startingMoney)
            currentTeam = leagueUser.team.toList
          // TODO log internal server errors as well
            currentTeamIds <- Try(currentTeam.map(tp => pickees.find(lp => lp.id == tp.pickeeId).get.externalId).toSet)
              .toOption.toRight(InternalServerError("Missing pickee externalId"))
            sellOrWildcard = if (applyWildcard) currentTeamIds else sell
            isValidPickees <- validatePickeeIds(currentTeamIds, pickees, sell, buy)
            newTeamIds = currentTeamIds ++ buy -- sellOrWildcard
            _ <- updatedTeamSize(newTeamIds, league)
            _ <- validateFactionLimit(newTeamIds, league)
            transferDelay = if (!league.started) None else Some(league.transferDelay)
            finished <- if (input.isCheck) Right(Ok("Transfers are valid")) else
              updateDBScheduleTransfer(sellOrWildcard, buy, pickees, leagueUser, league.currentPeriod.getOrElse(new Period()).value, newMoney, newRemaining, transferDelay, applyWildcard)
          } yield finished).fold(identity, identity)
        }
      }
    }

    transferForm.bindFromRequest().fold(failure, success)
  }

  private def updatedRemainingTransfers(leagueUser: LeagueUser, toSell: Set[Int]): Either[Result, Option[Int]] = {
    val newRemaining = leagueUser.remainingTransfers - toSell.size
    leagueUser.remainingTransfers match{
      case Some(x) if x - toSell.size < 0 => Left(BadRequest(
        f"Insufficient remaining transfers: $leagueUser.remainingTransfers"
      ))
      case Some(x) => Right(Some(x))
      case None => Right(None)
    }
  }

  private def validatePickeeIds(currentTeamIds: Set[Int], pickees: Iterable[Pickee], toSell: Set[Int], toBuy: Set[Int]): Either[Result, Boolean] = {
    // TODO return what ids are invalid
    (toSell ++ toBuy).subsetOf(pickees.map(_.externalId).toSet) match {
      case true => {
        (toBuy.intersect(currentTeamIds)).isEmpty match {
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

  private def updatedMoney(leagueUser: LeagueUser, pickees: Iterable[Pickee], toSell: Set[Int], toBuy: Set[Int], wildcardApplied: Boolean, startingMoney: Int): Either[Result, Int] = {
    val spent = pickees.filter(p => toBuy.contains(p.externalId)).map(_.cost).sum
    val updated = wildcardApplied match {
      case false => leagueUser.money + pickees.filter(p => toSell.contains(p.externalId)).map(_.cost).sum - spent
      case true => startingMoney - spent
    }
    updated match {
      case x if x >= 0 => Right(x)
      case x => Left(BadRequest(
        f"Insufficient credits. Transfers would leave user at ${CostConverter.convertCost(x)} credits"
      ))
    }
  }

  private def updatedTeamSize(newTeamIds: Set[Int], league: League): Either[Result, Int] = {
    newTeamIds.size match {
      case x if x <= league.teamSize => Right(x)
      case x => Left(BadRequest(
        f"Exceeds maximum team size of $league.teamSize"
      ))
    }
  }

  private def validateFactionLimit(newTeamIds: Set[Int], league: League): Either[Result, Any] = {
    //Right("cat")
    // TODO errrm this is a bit messy
    league.factionTypes.forall(factionType => {
      league.pickees.filter(lp => newTeamIds.contains(lp.externalId)).flatMap(_.factions).groupBy(_.factionTypeId)
        .forall({case (k, v) => v.size <= v.head.max})
    }) match {
        case true => Right(true)
        case false => Left(BadRequest(
          f"Exceeds faction limit"
        ))
      }
  }

//  private def processTransferOrJustCheck() = {
//    Right(Ok("Transfers are valid")) if input.isCheck else processTransfer(sell, buy, pickees, leagueUser.id, league.currentDay)
//  }

  private def updateDBScheduleTransfer(
                                toSell: Set[Int], toBuy: Set[Int], pickees: Iterable[Pickee], leagueUser: LeagueUser,
                                day: Int, newMoney: Int, newRemaining: Option[Int], transferDelay: Option[Int], applyWildcard: Boolean
                              ): Either[Result, Result] = {
    val scheduledUpdateTime = transferDelay.map(td => new Timestamp(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(td)))
    if (toSell.nonEmpty) {
      AppDB.transferTable.insert(toSell.map(ts => pickees.find(_.externalId == ts).get).map(
        p => new Transfer(
          leagueUser.id, p.id, false, scheduledUpdateTime.getOrElse(new Timestamp(System.currentTimeMillis())),
            scheduledUpdateTime.isEmpty, p.cost, applyWildcard
          )
      ))
      // TODO log internal server errors as well
      //currentTeamIds <- Try(leagueUser.team.map(tp => league.pickees.find(lp => lp.id == tp.pickeeId).get.externalId).toSet)
      if (scheduledUpdateTime.isEmpty) {
        AppDB.teamPickeeTable.deleteWhere(tp => tp.id in toSell.map(ts => pickees.find(_.externalId == ts).get.id))
      }
    }
    if (toBuy.nonEmpty) {
      AppDB.transferTable.insert(toBuy.map(tb => pickees.find(_.externalId == tb).get).map(
        p => new Transfer(leagueUser.id, p.id, true, scheduledUpdateTime.getOrElse(new Timestamp(System.currentTimeMillis())),
          scheduledUpdateTime.isEmpty, p.cost)
      ))
      // TODO day -1
      // TODO have active before tounr,manet start, but not after it started
      println(s"""pickees ${pickees.mkString(" ")}""")
      println(s""" extids ${pickees.map(_.externalId).mkString(" ")}""")
      println(s"""tobuy ${toBuy.mkString("")}""")
      if (scheduledUpdateTime.isEmpty) {
        AppDB.teamPickeeTable.insert(toBuy.map(tb => new TeamPickee(pickees.find(_.externalId == tb).get.id, leagueUser.id)))
      }
    }
    leagueUser.money = newMoney
    leagueUser.remainingTransfers = newRemaining
    leagueUser.changeTstamp = scheduledUpdateTime
    if (applyWildcard) leagueUser.usedWildcard = true
    AppDB.leagueUserTable.update(leagueUser)
    Right(Ok("Transfers successfully processed"))
  }

  private def processLeagueUserTransfer(leagueUser: LeagueUser) = {
    // TODO need to lock here?
    println("in proc trans")
    val transfers = AppDB.transferTable.where(t => t.processed === false and t.leagueUserId === leagueUser.id)
    AppDB.teamPickeeTable.insert(transfers.filter(_.isBuy).map(t => new TeamPickee(t.pickeeId, t.leagueUserId)))
    AppDB.teamPickeeTable.deleteWhere(tp =>
      (tp.leagueUserId === leagueUser.id) and (tp.pickeeId in transfers.filter(!_.isBuy))
    )
    AppDB.transferTable.update(transfers.map(t => {
      t.processed = true; t
    }))
    leagueUser.changeTstamp = None
    AppDB.leagueUserTable.update(leagueUser)
  }

  private def shouldApplyWildcard(attemptingWildcard: Boolean, league: League, leagueUser: LeagueUser, toSell: Set[Int]): Either[Result, Boolean] = {
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
