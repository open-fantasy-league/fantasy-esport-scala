package v1.team

import javax.inject.Inject
import play.api.mvc._
import play.api.libs.json._
import play.api.db._
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.concurrent.{ExecutionContext, Future}
import auth._
import v1.user.UserRepo
import v1.league.LeagueRepo
import play.api.db.Database
import utils.IdParser

case class TeamFormInput(buy: List[Int], sell: List[Int], isCheck: Boolean, delaySeconds: Option[Int])

class TeamController @Inject()(cc: ControllerComponents, userRepo: UserRepo, teamRepo: TeamRepo)
                              (implicit ec: ExecutionContext, db: Database, leagueRepo: LeagueRepo) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers
  implicit val parser = parse.default

  def getSingleTeamReq(leagueId: String, userId: String) = (
    new LeagueAction(leagueId) andThen new UserAction(userRepo, db)(userId).apply()).async { implicit request =>
      Future {
          (for {
            time <- IdParser.parseTimestamp(request.getQueryString("time"))
            out = db.withConnection { implicit c =>Json.toJson(teamRepo.getUserTeam(request.user.userId, time))}
          } yield Ok(out)).fold(identity, identity)
        }
  }

  def getCardsReq(leagueId: String, userId: String) = (
    new LeagueAction(leagueId) andThen new UserAction(userRepo, db)(userId).apply()).async { implicit request =>
    Future(Ok({
      db.withConnection{ implicit c => Json.toJson(teamRepo.getUserCards(request.user.userId))}
    }))
  }

  def getAllTeamsReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future {
      (for {
        time <- IdParser.parseTimestamp(request.getQueryString("time"))
        out = db.withConnection { implicit c =>Json.toJson(teamRepo.getAllUserTeam(request.league.leagueId, time))}
      } yield Ok(out)).fold(identity, identity)
    }
  }
}
