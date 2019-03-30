package v1.league

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.Inject
import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._

import play.api.db._
import utils.IdParser
import utils.TryHelper._
import auth._
import models.AppDB._
import anorm._
import models.{League, LimitType, Limit, LeagueRow,
  PickeeLimit
}
import v1.leagueuser.LeagueUserRepo
import v1.pickee.{PickeeRepo, PickeeFormInput}

case class PeriodInput(start: LocalDateTime, end: LocalDateTime, multiplier: Double)
case class UpdatePeriodInput(start: Option[LocalDateTime], end: Option[LocalDateTime], multiplier: Option[Double])

case class LimitInput(name: String, max: Option[Int])

case class LimitTypeInput(name: String, description: Option[String], max: Option[Int], types: List[LimitInput])

case class TransferInput(
                          transferLimit: Option[Int], transferDelayMinutes: Int, transferWildcard: Boolean,
                          transferBlockedDuringPeriod: Boolean, noWildcardForLateRegister: Boolean,
                        )

// TODO period descriptor
case class LeagueFormInput(name: String, gameId: Option[Long], isPrivate: Boolean, tournamentId: Long, periodDescription: String,
                           periods: List[PeriodInput], teamSize: Int, transferInfo: TransferInput, limits: List[LimitTypeInput],
                           startingMoney: BigDecimal, prizeDescription: Option[String], prizeEmail: Option[String],
                           extraStats: Option[List[String]],
                           pickeeDescription: String, pickees: List[PickeeFormInput], users: List[Int], apiKey: String,
                           applyPointsAtStartTime: Boolean, url: Option[String]
                          )

case class UpdateLeagueFormInput(name: Option[String], isPrivate: Option[Boolean],
                                 tournamentId: Option[Int], transferOpen: Option[Boolean],
                                 transferBlockedDuringPeriod: Option[Boolean],
                                 transferDelayMinutes: Option[Int],
                                 url: Option[String], transferLimit: Option[Int],
                                 transferWildcard: Option[Boolean],
                                 periodDescription: Option[String],
                                 pickeeDescription: Option[String],
                                 applyPointsAtStartTime: Option[Boolean], noWildcardForLateRegister: Option[Boolean]
                                 )

class LeagueController @Inject()(
                                  db: Database, cc: ControllerComponents,
                                  leagueUserRepo: LeagueUserRepo, pickeeRepo: PickeeRepo,
                                  auther: Auther
                                )(implicit ec: ExecutionContext, leagueRepo: LeagueRepo) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[LeagueFormInput] = {

    Form(
      mapping(
        "name" -> nonEmptyText,
        "gameId" -> optional(of(longFormat)),
        "isPrivate" -> boolean,
        "tournamentId" -> of(longFormat),
        "periodDescription" -> nonEmptyText,
        "periods" -> list(mapping(
          "start" -> of(localDateTimeFormat("yyyy-MM-dd HH:mm")),
          "end" -> of(localDateTimeFormat("yyyy-MM-dd HH:mm")),
          "multiplier" -> default(of(doubleFormat), 1.0)
        )(PeriodInput.apply)(PeriodInput.unapply)),
        "teamSize" -> default(number(min=1, max=20), 5),
        //"captain" -> default(boolean, false),
        "transferInfo" -> mapping(
          "transferLimit" -> optional(number),
          "transferDelayMinutes" -> default(number, 0),
          "transferWildcard" -> boolean,
          "transferBlockedDuringPeriod" -> default(boolean, false),
          "noWildcardForLateRegister" -> default(boolean, false),
        )(TransferInput.apply)(TransferInput.unapply),
        "limits" -> list(mapping(
          "name" -> nonEmptyText,
          "description" -> optional(nonEmptyText),
          "max" -> optional(number),
          "types" -> list(mapping(
            "name" -> nonEmptyText,
            "max" -> optional(number)
          )(LimitInput.apply)(LimitInput.unapply))
        )(LimitTypeInput.apply)(LimitTypeInput.unapply)),
        "startingMoney" -> default(bigDecimal(10, 1), BigDecimal.decimal(50.0)),
    // also singular prize with description and email fields
        "prizeDescription" -> optional(nonEmptyText),
        "prizeEmail" -> optional(nonEmptyText),
        // dont need a list of limits as input, as we just take them from their entry in pickee list
        "extraStats" -> optional(list(nonEmptyText)), // i.e. picks, wins. extra info to display on leaderboards other than points
        "pickeeDescription" -> nonEmptyText, //i.e. Hero for dota, Champion for lol, player for regular fantasy styles
        "pickees" -> list(mapping(
          "id" -> of(longFormat),
          "name" -> nonEmptyText,
          "value" -> bigDecimal(10, 1),
          "active" -> default(boolean, true),
          "limits" -> list(nonEmptyText),
        )(PickeeFormInput.apply)(PickeeFormInput.unapply)),
        "users" -> list(number),
        "apiKey" -> nonEmptyText,
        "applyPointsAtStartTime" -> default(boolean, true),
        "url" -> optional(nonEmptyText)
      )(LeagueFormInput.apply)(LeagueFormInput.unapply)
    )
  }

  private val updateForm: Form[UpdateLeagueFormInput] = {
    Form(
      mapping(
        "name" -> optional(nonEmptyText),
        "isPrivate" -> optional(boolean),
        "tournamentId" -> optional(number),
        "transferOpen" -> optional(boolean),
        "transferBlockedDuringPeriod" -> optional(boolean),
        "transferDelayMinutes" -> optional(number),
        "url" -> optional(nonEmptyText),
        "transferLimit" -> optional(number),
        "transferWildcard" -> optional(boolean),
        "periodDescription" -> optional(nonEmptyText),
        "pickeeDescription" -> optional(nonEmptyText),
        "applyPointsAtStartTime" -> optional(boolean),
        "noWildcardForLateRegister" -> optional(boolean),
      )(UpdateLeagueFormInput.apply)(UpdateLeagueFormInput.unapply)
    )
  }

  private val updatePeriodForm: Form[UpdatePeriodInput] = {
    Form(
      mapping(
        "start" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm"))),
        "end" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm"))),
        "multiplier" -> optional(of(doubleFormat)),
      )(UpdatePeriodInput.apply)(UpdatePeriodInput.unapply)
    )
  }

  implicit val parser = parse.default
  implicit val db_impl = db

  def get(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future(Ok(Json.toJson(request.league)))
  }

  def getWithRelatedReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future(inTransaction( db.withConnection { implicit c => Ok(Json.toJson(leagueRepo.getWithRelated(request.league.id)))}))
  }

  def update(leagueId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async { implicit request =>
    db.withConnection { implicit c => processJsonUpdateLeague(request.league)}
  }

  def add = Action.async(parse.json){implicit request => processJsonLeague()}

  def getAllUsersReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future {
      inTransaction {
        db.withConnection { implicit c =>
          val leagueUsers = leagueUserRepo.getAllUsersForLeague(request.league.id)
          Ok(Json.toJson(leagueUsers))
        }
      }
    }
  }

  def showLeagueUserReq(userId: String, leagueId: String) = (new LeagueAction(leagueId)
    andThen db.withConnection { implicit c => new LeagueUserAction(userId).apply(Some(leagueUserRepo.joinUsers2))}).async { implicit request =>
    Future{
      inTransaction{
      val showTeam = !request.getQueryString("team").isEmpty
      val showScheduledTransfers = !request.getQueryString("scheduledTransfers").isEmpty
      val stats = !request.getQueryString("stats").isEmpty
      Ok(Json.toJson(leagueUserRepo.detailedLeagueUser(request.user, request.leagueUser, showTeam, showScheduledTransfers, stats)))
    }}
  }

  def getRankingsReq(leagueId: String, statFieldName: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future {
      inTransaction {
        db.withConnection { implicit c =>
          val users = request.getQueryString("users").map(_.split(",").map(_.toLong))
          val secondaryOrdering = request.getQueryString("secondary").map(_.split(",").toList.map(s => leagueUserRepo.getStatField(request.league.id, s).get.id))
          (for {
            statField <- leagueUserRepo.getStatField(request.league.id, statFieldName).toRight(BadRequest("Unknown stat field"))
            period <- tryOrResponse[Option[Int]](() => request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
            includeTeam = request.getQueryString("team")
            rankings = leagueUserRepo.getRankings(request.league, statField, period, includeTeam.isDefined, users, secondaryOrdering)
            out = Ok(Json.toJson(rankings))
          } yield out).fold(identity, identity)
        }
      }
    }
  }

  def endPeriodReq(leagueId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async {implicit request =>
    Future {
      inTransaction {
        db.withConnection { implicit c =>
          leagueRepo.getCurrentPeriod(request.league) match {
            case Some(p) if !p.ended => {
              leagueRepo.postEndPeriodHook(List(p.id), List(request.league.id), LocalDateTime.now())
              Ok("Successfully ended day")
            }
            case _ => BadRequest("Period already ended (Must start next period first)")
          }
        }
      }
    }
  }

  def startPeriodReq(leagueId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async {implicit request =>
    Future {
      inTransaction {
        db.withConnection { implicit c =>
          (for {
            newPeriod <- leagueRepo.getNextPeriod(request.league)
            _ = leagueRepo.postStartPeriodHook(request.league, newPeriod, LocalDateTime.now())
            out = Ok(f"Successfully started period $newPeriod")
          } yield out).fold(identity, identity)
        }
      }
    }
  }

//  def getHistoricTeamsReq(leagueId: String, period: String) =
//  {
//    (new LeagueAction(leagueId) andThen new PeriodAction().league()).async { implicit request =>
//    Future(inTransaction(Ok(Json.toJson(leagueUserRepo.getHistoricTeams(request.league, request.p.get)))))
//  }}

  def getCurrentTeamsReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future(inTransaction(Ok(Json.toJson(leagueUserRepo.getCurrentTeams(request.league.id)))))
  }

  def updatePeriodReq(leagueId: String, periodValue: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async { implicit request =>
    Future {
      inTransaction {
        db.withConnection { implicit c =>
          (for {
            periodValueInt <- IdParser.parseIntId(periodValue, "period value")
            out = handleUpdatePeriodForm(request.league.id, periodValueInt)
          } yield out).fold(identity, identity)
        }
      }
    }
  }

  private def handleUpdatePeriodForm[A](leagueId: Long, periodValue: Int)(implicit request: Request[A]): Result = {
    def failure(badForm: Form[UpdatePeriodInput]) = {
      BadRequest(badForm.errorsAsJson)
    }

    def success(input: UpdatePeriodInput) = {
      (for {
        updatedPeriod <- tryOrResponse(() => leagueRepo.updatePeriod(leagueId, periodValue, input.start, input.end, input.multiplier), BadRequest("Invalid leagueId or period value"))
        out = Ok(Json.toJson(updatedPeriod))
      } yield out).fold(identity, identity)
    }
    updatePeriodForm.bindFromRequest().fold(failure, success)
  }

  private def processJsonLeague[A]()(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[LeagueFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: LeagueFormInput): Future[Result] = {
      Future{
      inTransaction {
        db.withConnection { implicit c =>
          val newLeague = leagueRepo.insert(input)
          (input.prizeDescription, input.prizeEmail) match {
            case (Some(d), Some(e)) => leagueRepo.insertLeaguePrize(newLeague.id, d, e)
            case (Some(d), None) => return Future.successful(BadRequest("Must enter prize email with description"))
            case (None, Some(e)) => return Future.successful(BadRequest("Must enter prize description with email"))
            case _ => ;
          }

          val pointsField = leagueRepo.insertLeagueStatField(newLeague.id, "points")
          val statFields = List(pointsField.id) ++ input.extraStats.getOrElse(Nil).map(es => leagueRepo.insertLeagueStatField(newLeague.id, es).id)

          val newPickeeIds = input.pickees.map(pickeeRepo.insertPickee(newLeague.id, _).id)
          val newLeagueUsers = input.users.map(leagueUserRepo.insertLeagueUser(newLeague, _))
          val newPickeeStats = statFields.flatMap(sf => newPickeeIds.map(np => pickeeRepo.insertPickeeStat(sf, np)))
          val newLeagueUserStats = statFields.flatMap(sf => newLeagueUsers.map(nlu => leagueUserRepo.insertLeagueUserStat(sf, nlu.id)))

          newPickeeStats.foreach(np => pickeeRepo.insertPickeeStatDaily(np.id, None))
          newLeagueUserStats.foreach(nlu => leagueUserRepo.insertLeagueUserStatDaily(nlu.id, None))
          var nextPeriodId: Option[Long] = None
          // have to be inserted 'back to front', so that we can know and specify id of nextPeriod, and link them.
          input.periods.zipWithIndex.reverse.foreach({ case (p, i) => {
            val newPeriod = leagueRepo.insertPeriod(newLeague.id, p, i + 1, nextPeriodId)
            nextPeriodId = Some(newPeriod.id)
            newPickeeStats.foreach(np => pickeeRepo.insertPickeeStatDaily(np.id, Some(i + 1)))
            newLeagueUserStats.foreach(nlu => leagueUserRepo.insertLeagueUserStatDaily(nlu.id, Some(i + 1)))
          }
          })
          val limitNamesToIds = collection.mutable.Map[String, Long]()

          input.limits.foreach(ft => {
            val newLimitType = limitTypeTable.insert(
              new LimitType(newLeague.id, ft.name, ft.description.getOrElse(ft.name), ft.max)
            )
            ft.types.foreach(f => {
              val newLimit = limitTable.insert(new Limit(newLimitType.id, f.name, ft.max.getOrElse(f.max.get)))
              limitNamesToIds(newLimit.name) = newLimit.id.toLong
            })
          })
          input.pickees.zipWithIndex.foreach({ case (p, i) => p.limits.foreach({
            // Try except key error
            f => pickeeLimitTable.insert(new PickeeLimit(newPickeeIds(i), limitNamesToIds(f)))
          })
          })

          Created(Json.toJson(newLeague))
        }
      }
      }
    }

    form.bindFromRequest().fold(failure, success)
  }

  private def processJsonUpdateLeague[A](league: LeagueRow)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[UpdateLeagueFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: UpdateLeagueFormInput) = Future(
      if (leagueRepo.isStarted(league) && (input.transferLimit.isDefined || input.transferWildcard.isDefined)){
        BadRequest("Cannot update transfer limits or wildcard after league has started")
      } else{
        db.withConnection { implicit c =>
          inTransaction(Ok(Json.toJson(leagueRepo.update(league, input))))
        }
      }
    )

    updateForm.bindFromRequest().fold(failure, success)
  }
}
