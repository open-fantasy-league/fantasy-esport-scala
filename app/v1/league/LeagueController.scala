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
import models.AppDB._
import models.{League, Pickee, PickeeStat, LeagueUserStat, LeagueUserStatDaily, LeagueStatField, LeaguePlusStuff}
import v1.leagueuser.LeagueUserRepo
import v1.pickee.{PickeeRepo, PickeeFormInput}

case class LeagueFormInput(name: String, gameId: Int, isPrivate: Boolean, tournamentId: Int, totalDays: Int,
                           dayStart: Long, dayEnd: Long, teamSize: Int, transferLimit: Option[Int], factionLimit: Option[Int],
                           factionDescription: Option[String], startingMoney: Double,
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
                                )(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[LeagueFormInput] = {

    Form(
      mapping(
        "name" -> nonEmptyText,
        "gameId" -> number,
        "isPrivate" -> boolean,
        "tournamentId" -> number,
        "totalDays" -> number(min=1, max=100),
        "dayStart" -> longNumber,
        "dayEnd" -> longNumber,
        "teamSize" -> default(number(min=1, max=20), 5),
        //"captain" -> default(boolean, false),
        "transferLimit" -> optional(number), // use -1 for no transfer limit I think
        "factionLimit" -> optional(number),  // i.e. 2 for max 2 eg players per team
        "factionDescription" -> optional(nonEmptyText),  // i.e. Team for describing factions as being different teams. Race for sc2 races
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
          "faction" -> optional(nonEmptyText)  // e.g. Evil Geniuses, Zerg...etc
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
          statFields = leagueRepo.getStatFieldNames(league.statFields)
          finished = Ok(Json.toJson(LeaguePlusStuff(league, statFields)))
        } yield finished).fold(identity, identity)
      }
    }
  }

  def update(leagueId: String) = Action.async(parse.json) { implicit request =>
    processJsonUpdateLeague(leagueId)
  }

  def add = Action.async(parse.json){ implicit request =>
    processJsonLeague()
//    scala.concurrent.Future{ Ok(views.html.index())}
  }

  def getRankingsReq(leagueId: String, statFieldName: String) = Action.async { implicit request =>
    Future {
      inTransaction {
        (for {
          leagueId <- IdParser.parseIntId(leagueId, "league")
          statField <- leagueUserRepo.getStatField(leagueId, statFieldName).toRight(BadRequest("Unknown stat field"))
          league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league id"))
          rankings <- tryOrResponse(
            () => leagueUserRepo.getRankings(league, statField, None), InternalServerError("internal Server Error")
          )
          out = Ok(Json.toJson(rankings))
        } yield out).fold(identity, identity)
      }
    }
  }

  def updateOldRanksReq(leagueId: String) = Action.async(parse.json){ implicit request =>
    Future {
      inTransaction {
        (for {
          leagueId <- IdParser.parseIntId(leagueId, "league")
          _ <- updateOldRanks(leagueId)
          out = Ok("Updated old ranking")
        } yield out).fold(identity, identity)
      }
    }
  }

  def storeHistoricTeamsReq(leagueId: String) = Action.async { implicit request =>
    Future {
      inTransaction {
        (for {
          leagueId <- IdParser.parseIntId(leagueId, "league")
          league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league id"))
          out <- addHistoricTeam(leagueId)
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

  def incrementDayReq(leagueId: String) = Action.async { implicit request =>
    Future {
      inTransaction {
        (for {
          leagueId <- IdParser.parseIntId(leagueId, "league")
          league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league id"))
          _ = leagueRepo.incrementDay(league)
          out = Ok("Incremented day")
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

        // TODO make sure stat fields static cant be changed once tournament in progress
        //statFields = statFields ++ input.extraStats.flatMap(es => leagueRepo.insertLeagueStatField(newLeague.id, es).id)

        for (pickee <- input.pickees) {
          val newPickee = pickeeRepo.insertPickee(newLeague.id, pickee)

          // -1 is for whole tournament
          statFields.foreach(
            statFieldId => {
              val newPickeeStat = pickeeRepo.insertPickeeStat(statFieldId, newPickee.id)
              pickeeRepo.insertPickeeStatDaily(newPickeeStat.id, None)
              (1 to input.totalDays).foreach(d => {
                pickeeRepo.insertPickeeStatDaily(newPickeeStat.id, Some(d))
              })
          })
        }

        for (userId <- input.users) {
          val newLeagueUser = leagueUserRepo.insertLeagueUser(newLeague, userId)

          statFields.foreach(
            statFieldId => {
              val newLeagueUserStat = leagueUserRepo.insertLeagueUserStat(statFieldId, newLeagueUser.id)
              leagueUserRepo.insertLeagueUserStatDaily(newLeagueUserStat.id, None)
              (1 to input.totalDays).foreach(d => {
                leagueUserRepo.insertLeagueUserStatDaily(newLeagueUserStat.id, Some(d))
              })
          })
        }

        Future {
          Created(Json.toJson(newLeague))
        }
        //Future{Ok(views.html.index())}
        //scala.concurrent.Future{ Ok(views.html.index())}
        //      postResourceHandler.create(input).map { post =>
        //      Created(Json.toJson(post)).withHeaders(LOCATION -> post.link)
        //      }
        // TODO good practice post-redirect-get
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
          // TODO handle invalid Id
          val leagueQuery = leagueRepo.get(Integer.parseInt(leagueId))
          leagueQuery match {
            case Some(league) => Ok(Json.toJson(leagueRepo.update(league, input)))
            case None => BadRequest("Specified league id does not exist")
          }
        }
        //scala.concurrent.Future{ Ok(views.html.index())}
        //      postResourceHandler.create(input).map { post =>
        //      Created(Json.toJson(post)).withHeaders(LOCATION -> post.link)
        //      }
        // TODO good practice post-redirect-get
      }
    }

    updateForm.bindFromRequest().fold(failure, success)
  }

  private def updateOldRanks(leagueId: Int): Either[Result, Any] = {
    // TODO also update pickee ranks
    // TODO this needs to group by the stat field.
    // currently will do weird ranks
    for {
      league <- leagueRepo.get(leagueId).toRight(BadRequest("Unknown league"))
      statFieldIds = league.statFields.map(_.id)
      _ = statFieldIds.map(sId => {
        val leagueUserStatsOverall = leagueUserRepo.getLeagueUserStat(leagueId, sId, None).map(_._1)
        val newLeagueUserStat = leagueUserStatsOverall.zipWithIndex.map(
          { case (lus, i) => lus.previousRank = i + 1; lus }
        )
        // can do all update in one call if append then update outside loop
        leagueUserRepo.updateLeagueUserStat(newLeagueUserStat)
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
