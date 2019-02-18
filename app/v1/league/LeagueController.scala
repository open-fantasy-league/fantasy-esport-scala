package v1.league

import java.sql.Timestamp

import javax.inject.Inject
import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import utils.IdParser
import utils.TryHelper._
import auth._
import models.AppDB._
import models.{League, FactionType, Faction,
  PickeeFaction
}
import v1.leagueuser.LeagueUserRepo
import v1.pickee.{PickeeRepo, PickeeFormInput}

case class PeriodInput(start: Timestamp, end: Timestamp, multiplier: Double)
case class UpdatePeriodInput(start: Option[Timestamp], end: Option[Timestamp], multiplier: Option[Double])

case class FactionInput(name: String, max: Option[Int])

case class FactionTypeInput(name: String, description: Option[String], max: Option[Int], types: List[FactionInput])


// TODO period descriptor
case class LeagueFormInput(name: String, gameId: Option[Long], isPrivate: Boolean, tournamentId: Long, periodDescription: String,
                           periods: List[PeriodInput], teamSize: Int, transferLimit: Option[Int],
                           transferWildcard: Boolean, transferBlockedDuringPeriod: Boolean, factions: List[FactionTypeInput],
                           startingMoney: BigDecimal,
                           transferDelayMinutes: Int, prizeDescription: Option[String], prizeEmail: Option[String],
                           extraStats: Option[List[String]],
                           // TODO List is linked lsit. check thats fine. or change to vector
                           pickeeDescription: String, pickees: List[PickeeFormInput], users: List[Int], apiKey: String, url: Option[String]
                          )

case class UpdateLeagueFormInput(name: Option[String], isPrivate: Option[Boolean],
                                 tournamentId: Option[Int], transferOpen: Option[Boolean],
                                 transferBlockedDuringPeriod: Option[Boolean],
                                 transferDelayMinutes: Option[Int],
                                 url: Option[String], transferLimit: Option[Int],
                                 transferWildcard: Option[Boolean],
                                 periodDescription: Option[String],
                                 pickeeDescription: Option[String]
                                 )

class LeagueController @Inject()(
                                  cc: ControllerComponents, leagueRepo: LeagueRepo,
                                  leagueUserRepo: LeagueUserRepo, pickeeRepo: PickeeRepo,
                                  auther: Auther
                                )(implicit ec: ExecutionContext) extends AbstractController(cc)
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
          "start" -> of(sqlTimestampFormat("yyyy-MM-dd HH:mm")),
          "end" -> of(sqlTimestampFormat("yyyy-MM-dd HH:mm")),
          "multiplier" -> default(of(doubleFormat), 1.0)
        )(PeriodInput.apply)(PeriodInput.unapply)),
        "teamSize" -> default(number(min=1, max=20), 5),
        //"captain" -> default(boolean, false),
        "transferLimit" -> optional(number), // use -1 for no transfer limit I think
        "transferWildcard" -> boolean,
        "transferBlockedDuringPeriod" -> default(boolean, false),
        "factions" -> list(mapping(
          "name" -> nonEmptyText,
          "description" -> optional(nonEmptyText),
          "max" -> optional(number),
          "types" -> list(mapping(
            "name" -> nonEmptyText,
            "max" -> optional(number)
          )(FactionInput.apply)(FactionInput.unapply))
        )(FactionTypeInput.apply)(FactionTypeInput.unapply)),
        "startingMoney" -> default(bigDecimal(10, 1), BigDecimal.decimal(50.0)),
        "transferDelayMinutes" -> default(number, 0),
        //"factions" -> List of stuff
    // also singular prize with description and email fields
        "prizeDescription" -> optional(nonEmptyText),
        "prizeEmail" -> optional(nonEmptyText),
        // dont need a list of factions as input, as we just take them from their entry in pickee list
        "extraStats" -> optional(list(nonEmptyText)), // i.e. picks, wins. extra info to display on leaderboards other than points
        "pickeeDescription" -> nonEmptyText, //i.e. Hero for dota, Champion for lol, player for regular fantasy styles
        "pickees" -> list(mapping(
          "id" -> of(longFormat),
          "name" -> nonEmptyText,
          "value" -> bigDecimal(10, 1),
          "active" -> default(boolean, true),
          "factions" -> list(nonEmptyText),
        )(PickeeFormInput.apply)(PickeeFormInput.unapply)),
        "users" -> list(number),
        "apiKey" -> nonEmptyText,
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
        "pickeeDescription" -> optional(nonEmptyText)
      )(UpdateLeagueFormInput.apply)(UpdateLeagueFormInput.unapply)
    )
  }

  private val updatePeriodForm: Form[UpdatePeriodInput] = {
    Form(
      mapping(
        "start" -> optional(of(sqlTimestampFormat("yyyy-MM-dd HH:mm"))),
        "end" -> optional(of(sqlTimestampFormat("yyyy-MM-dd HH:mm"))),
        "multiplier" -> optional(of(doubleFormat)),
      )(UpdatePeriodInput.apply)(UpdatePeriodInput.unapply)
    )
  }

  implicit val parser = parse.default

  def get(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future(Ok(Json.toJson(request.league)))
  }

  def getWithRelatedReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future(inTransaction(Ok(Json.toJson(leagueRepo.getWithRelated(request.league.id)))))
  }

  def update(leagueId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async { implicit request =>
    processJsonUpdateLeague(request.league)
  }

  def add = Action.async(parse.json){implicit request => processJsonLeague()}

  def getAllUsersReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future {
      inTransaction {
        val leagueUsers = leagueUserRepo.getAllUsersForLeague(request.league.id)
        Ok(Json.toJson(leagueUsers))
      }
    }
  }

  def showLeagueUserReq(userId: String, leagueId: String) = (new LeagueAction(leagueId) andThen new LeagueUserAction(userId).apply(Some(leagueUserRepo.joinUsers))).async { implicit request =>
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
        (for {
          statField <- leagueUserRepo.getStatField(request.league.id, statFieldName).toRight(BadRequest("Unknown stat field"))
          period <- tryOrResponse[Option[Int]](() => request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          includeTeam = request.getQueryString("team")
          /*rankings <- tryOrResponse(
            () => leagueUserRepo.getRankings(league, statField, period), InternalServerError("internal Server Error")
          )*/
          rankings = leagueUserRepo.getRankings(request.league, statField, period, !includeTeam.isEmpty)
          out = Ok(Json.toJson(rankings))
        } yield out).fold(identity, identity)
      }
    }
  }

  def endPeriodReq(leagueId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async {implicit request =>
    Future {
      inTransaction {
        request.league.currentPeriod match {
          case Some(p) if !p.ended => {
            leagueRepo.postEndPeriodHook(request.league, p)
            Ok("Successfully ended day")
          }
          case _ => BadRequest("Period already ended (Must start next period first)")
        }
      }
    }
  }

  def startPeriodReq(leagueId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async {implicit request =>
    println(request.apiKey)
    println(request.league)
    Future {
      inTransaction {
        (for {
          newPeriod <- leagueRepo.getNextPeriod(request.league)
          _ = leagueRepo.postStartPeriodHook(request.league, newPeriod)
          out = Ok(f"Successfully started period $newPeriod") // TODO replace with period descriptor
        } yield out).fold(identity, identity)
      }
    }
  }

  def getHistoricTeamsReq(leagueId: String, period: String) = 
  {
    (new LeagueAction(leagueId) andThen new PeriodAction().league()).async { implicit request =>
    Future(inTransaction(Ok(Json.toJson(leagueUserRepo.getHistoricTeams(request.league, request.p.get)))))
  }}

  def getCurrentTeamsReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future(inTransaction(Ok(Json.toJson(leagueUserRepo.getCurrentTeams(request.league.id)))))
  }

  def updatePeriodReq(leagueId: String, periodValue: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async { implicit request =>
    Future {
      inTransaction {
        (for {
          periodValueInt <- IdParser.parseIntId(periodValue, "period value")
          out = handleUpdatePeriodForm(request.league.id, periodValueInt)
        } yield out).fold(identity, identity)
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
      println("yay")
      Future{
      inTransaction {
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
        input.periods.zipWithIndex.reverse.foreach({case (p, i) => {
          val newPeriod = leagueRepo.insertPeriod(newLeague.id, p, i+1, nextPeriodId)
          nextPeriodId = Some(newPeriod.id)
          newPickeeStats.foreach(np => pickeeRepo.insertPickeeStatDaily(np.id, Some(i+1)))
          newLeagueUserStats.foreach(nlu => leagueUserRepo.insertLeagueUserStatDaily(nlu.id, Some(i+1)))
        }})
        val factionNamesToIds =  collection.mutable.Map[String, Long]()

        input.factions.foreach(ft => {
          val newFactionType = factionTypeTable.insert(
            new FactionType(newLeague.id, ft.name, ft.description.getOrElse(ft.name), ft.max)
          )
          ft.types.foreach(f => {
            val newFaction = factionTable.insert(new Faction(newFactionType.id, f.name, ft.max.getOrElse(f.max.get)))
            factionNamesToIds(newFaction.name) = newFaction.id.toLong
          })
          //if (input.pickees.flatMap(_.factions) not in factionNames)
          // rollback
          // BadRequest("Invalid pickee faction type given")
        })
        input.pickees.zipWithIndex.foreach({case (p, i) => p.factions.foreach({
          // Try except key error
          f => pickeeFactionTable.insert(new PickeeFaction(newPickeeIds(i), factionNamesToIds.get(f).get))
        })})

          Created(Json.toJson(newLeague))
        }
      }
    }

    form.bindFromRequest().fold(failure, success)
  }

  private def processJsonUpdateLeague[A](league: League)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[UpdateLeagueFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: UpdateLeagueFormInput) = Future(
      if (league.started && (input.transferLimit.isDefined || input.transferWildcard.isDefined)){
        BadRequest("Cannot update transfer limits or wildcard after league has started")
      } else{
        inTransaction(Ok(Json.toJson(leagueRepo.update(league, input))))
      }
    )

    updateForm.bindFromRequest().fold(failure, success)
  }
}
