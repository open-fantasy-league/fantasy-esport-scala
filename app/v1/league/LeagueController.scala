package v1.league

import scala.collection.mutable.ArrayBuffer
import java.sql.Timestamp

import javax.inject.Inject
import entry.SquerylEntrypointForMyApp._

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import utils.{CostConverter, IdParser}
import utils.TryHelper._
import auth.AuthAction
import models.AppDB._
import models.{League, Pickee, PickeeStat, LeagueUserStat, LeagueUserStatDaily, LeagueStatField, FactionType, Faction,
  PickeeFaction
}
import v1.leagueuser.LeagueUserRepo
import v1.pickee.{PickeeRepo, PickeeFormInput}

case class PeriodInput(start: Timestamp, end: Timestamp, multiplier: Double)

case class FactionInput(name: String, max: Option[Int])

case class FactionTypeInput(name: String, description: Option[String], max: Option[Int], types: List[FactionInput])


// TODO period descriptor
case class LeagueFormInput(name: String, gameId: Int, isPrivate: Boolean, tournamentId: Int, periodDescription: String,
                           periods: List[PeriodInput], teamSize: Int, transferLimit: Option[Int],
                           transferWildcard: Boolean, factions: List[FactionTypeInput], startingMoney: Double,
                           transferDelay: Int, prizeDescription: Option[String], prizeEmail: Option[String],
                           extraStats: Option[List[String]],
                           // TODO List is linked lsit. check thats fine. or change to vector
                           pickeeDescription: String, pickees: List[PickeeFormInput], users: List[Int]
                          )

case class UpdateLeagueFormInput(name: Option[String], isPrivate: Option[Boolean],
                                 tournamentId: Option[Int], totalDays: Option[Int],
                           dayStart: Option[Long], dayEnd: Option[Long], transferOpen: Option[Boolean])

class LeagueController @Inject()(
                                  cc: ControllerComponents, leagueRepo: LeagueRepo,
                                  leagueUserRepo: LeagueUserRepo, pickeeRepo: PickeeRepo,
                                  authAct: AuthAction
                                )(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[LeagueFormInput] = {

    Form(
      mapping(
        "name" -> nonEmptyText,
        "gameId" -> number,
        "isPrivate" -> boolean,
        "tournamentId" -> number,
        "periodDescription" -> nonEmptyText,
        "periods" -> list(mapping(
          "start" -> of(sqlTimestampFormat),
          "end" -> of(sqlTimestampFormat),
          "multiplier" -> default(of(doubleFormat), 1.0)
        )(PeriodInput.apply)(PeriodInput.unapply)),
        "teamSize" -> default(number(min=1, max=20), 5),
        //"captain" -> default(boolean, false),
        "transferLimit" -> optional(number), // use -1 for no transfer limit I think
        "transferWildcard" -> boolean,
        "factions" -> list(mapping(
          "name" -> nonEmptyText,
          "description" -> optional(nonEmptyText),
          "max" -> optional(number),
          "types" -> list(mapping(
            "name" -> nonEmptyText,
            "max" -> optional(number)
          )(FactionInput.apply)(FactionInput.unapply))
        )(FactionTypeInput.apply)(FactionTypeInput.unapply)),
        "startingMoney" -> default(of(doubleFormat), 50.0),
        "transferDelay" -> default(number, 0),
        //"factions" -> List of stuff
    // also singular prize with description and email fields
        "prizeDescription" -> optional(nonEmptyText),
        "prizeEmail" -> optional(nonEmptyText),
        // dont need a list of factions as input, as we just take them from their entry in pickee list
        "extraStats" -> optional(list(nonEmptyText)), // i.e. picks, wins. extra info to display on leaderboards other than points
        "pickeeDescription" -> nonEmptyText, //i.e. Hero for dota, Champion for lol, player for regular fantasy styles
        "pickees" -> list(mapping(
          "id" -> number,
          "name" -> nonEmptyText,
          "value" -> of(doubleFormat),
          "active" -> default(boolean, true),
          "factions" -> list(nonEmptyText),
          "imgUrl" -> optional(nonEmptyText),
        )(PickeeFormInput.apply)(PickeeFormInput.unapply)),
        "users" -> list(number)
      )(LeagueFormInput.apply)(LeagueFormInput.unapply)
    )
  }

  private val updateForm: Form[UpdateLeagueFormInput] = {

    Form(
      mapping(
        "name" -> optional(nonEmptyText),
        "isPrivate" -> optional(boolean),
        "tournamentId" -> optional(number),
        "totalDays" -> optional(number(min=1, max=100)),  // todo add handling for increasing/decreasing num days
        "dayStart" -> optional(longNumber),
        "dayEnd" -> optional(longNumber),
        "transferOpen" -> optional(boolean),
      )(UpdateLeagueFormInput.apply)(UpdateLeagueFormInput.unapply)
    )
  }

  def get(leagueId: String) = Action.async { implicit request =>
    Future {
      inTransaction {
        (for {
          leagueId <- IdParser.parseIntId(leagueId, "league")
          league <- leagueRepo.get(leagueId).toRight(NotFound(f"League id $leagueId does not exist"))
          finished = Ok(Json.toJson(league))
        } yield finished).fold(identity, identity)
      }
    }
  }

  def getWithRelatedReq(leagueId: String) = Action.async { implicit request =>
    Future {
      inTransaction {
        (for {
          leagueId <- IdParser.parseIntId(leagueId, "league")
          league <- leagueRepo.getWithRelated(leagueId).toRight(NotFound(f"League id $leagueId does not exist"))
          finished = Ok(Json.toJson(league))
        } yield finished).fold(identity, identity)
      }
    }
  }

  def update(leagueId: String) = Action.async(parse.json) { implicit request =>
    processJsonUpdateLeague(leagueId)
  }

  def add = Action.async(parse.json){ implicit request =>
    processJsonLeague()
  }

  def getRankingsReq(leagueId: String, statFieldName: String) = Action.async { implicit request =>
    Future {
      inTransaction {
        (for {
          leagueId <- IdParser.parseIntId(leagueId, "league")
          statField <- leagueUserRepo.getStatField(leagueId, statFieldName).toRight(BadRequest("Unknown stat field"))
          league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league id"))
          day <- tryOrResponse[Option[Int]](() => request.getQueryString("day").map(_.toInt), BadRequest("Invalid day format"))
          rankings <- tryOrResponse(
            () => leagueUserRepo.getRankings(league, statField, day), InternalServerError("internal Server Error")
          )
          out = Ok(Json.toJson(rankings))
        } yield out).fold(identity, identity)
      }
    }
  }

  def endDayReq(leagueId: String) = authAct.async {implicit request =>
    Future {
      inTransaction {
        (for {
          leagueId <- IdParser.parseIntId(leagueId, "league")
          league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league id"))
          _ <- league.currentPeriod match {
            case Some(p) if !p.ended => {
              p.ended = true
              periodTable.update(p)
              Right(true)
            }
            case _ => Left(BadRequest("Period already ended (Must start next period first)"))
          }
          out <- addHistoricTeam(leagueId)
        } yield out).fold(identity, identity)
      }
    }
  }

  def startDayReq(leagueId: String) = authAct.async {implicit request =>
    Future {
      inTransaction {
        (for {
          leagueId <- IdParser.parseIntId(leagueId, "league")
          league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league id"))
          newPeriod <- leagueRepo.incrementDay(league)
          _ <- updateOldRanks(leagueId)
          out = Ok(f"Successfully started day $newPeriod") // TODO replace with period descriptor
        } yield out).fold(identity, identity)
      }
    }
  }

  def getHistoricTeamsReq(leagueId: String, day: String) = Action.async { implicit request =>
    Future {
      inTransaction {
        (for {
          day <- IdParser.parseIntId(day, "day")
          leagueId <- IdParser.parseIntId(leagueId, "league")
          league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league id"))
          out = Ok(Json.toJson(leagueUserRepo.getHistoricTeams(league, day)))
        } yield out).fold(identity, identity)
      }
    }
  }

  private def processJsonLeague[A]()(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[LeagueFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: LeagueFormInput) = {
      println("yay")
      inTransaction {
        val newLeague = leagueRepo.insert(input)

        val pointsField = leagueRepo.insertLeagueStatField(newLeague.id, "points")
        val statFields = List(pointsField.id) ++ input.extraStats.getOrElse(Nil).map(es => leagueRepo.insertLeagueStatField(newLeague.id, es).id)

        val newPickeeIds = input.pickees.map(pickeeRepo.insertPickee(newLeague.id, _).id)
        val newLeagueUsers = input.users.map(leagueUserRepo.insertLeagueUser(newLeague, _))
        val newPickeeStats = statFields.flatMap(sf => newPickeeIds.map(np => pickeeRepo.insertPickeeStat(sf, np)))
        val newLeagueUserStats = statFields.flatMap(sf => newLeagueUsers.map(nlu => leagueUserRepo.insertLeagueUserStat(sf, nlu.id)))

        newPickeeStats.foreach(np => pickeeRepo.insertPickeeStatDaily(np.id, None))
        newLeagueUserStats.foreach(nlu => leagueUserRepo.insertLeagueUserStatDaily(nlu.id, None))

        (input.periods.zipWithIndex).foreach({case (p, i) => {
          leagueRepo.insertPeriod(newLeague.id, p, i+1)
          newPickeeStats.foreach(np => pickeeRepo.insertPickeeStatDaily(np.id, Some(i+1)))
          newLeagueUserStats.foreach(nlu => leagueUserRepo.insertLeagueUserStatDaily(nlu.id, Some(i+1)))
        }})
        var factionNamesToIds =  collection.mutable.Map[String, Long]()

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

        Future {
          Created(Json.toJson(newLeague))
        }
      }
    }

    form.bindFromRequest().fold(failure, success)
  }

  private def processJsonUpdateLeague[A](leagueId: String)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[UpdateLeagueFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: UpdateLeagueFormInput) = {
      println("yay")
      Future {
        inTransaction {
          (for {
            leagueId <- IdParser.parseIntId(leagueId, "league")
            league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league id"))
            out = Ok(Json.toJson(leagueRepo.update(league, input)))
          } yield out).fold(identity, identity)
        }
      }
    }

    updateForm.bindFromRequest().fold(failure, success)
  }

  private def updateOldRanks(leagueId: Int): Either[Result, Any] = {
    // TODO this needs to group by the stat field.
    // currently will do weird ranks
    for {
      league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league"))
      statFieldIds = league.statFields.map(_.id)
      _ = statFieldIds.map(sId => {
        val leagueUserStatsOverall: Iterable[LeagueUserStat] =
          leagueUserRepo.getLeagueUserStat(leagueId, sId, None).map(_._1)
        val newLeagueUserStat = leagueUserStatsOverall.zipWithIndex.map(
          { case (lus, i) => lus.previousRank = i + 1; lus }
        )
        // can do all update in one call if append then update outside loop
        leagueUserRepo.updateLeagueUserStat(newLeagueUserStat)
        val pickeeStatsOverall = pickeeRepo.getPickeeStat(leagueId, sId, None).map(_._1)
        val newPickeeStat = pickeeStatsOverall.zipWithIndex.map(
          { case (p, i) => p.previousRank = i + 1; p }
        )
        // can do all update in one call if append then update outside loop
        pickeeStatTable.update(newPickeeStat)
      })
      out = Right(Ok("Updated previous ranks"))
    } yield out
  }

  private def addHistoricTeam(leagueId: Int): Either[Result, Result] = {
    for {
      league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league"))
      _ = leagueUserRepo.addHistoricTeams(league)
      out <- Right(Ok("Updated old ranks and historic team"))
    } yield out
  }
}
