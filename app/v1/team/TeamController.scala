package v1.team

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
import models.AppDB._
import models.{League, LeagueUser, Pickee, TeamPickee}
import utils.{IdParser, CostConverter}
import auth._
import v1.leagueuser.LeagueUserRepo

case class TeamFormInput(buy: List[Int], sell: List[Int], isCheck: Boolean, delaySeconds: Option[Int])

class TeamController @Inject()(cc: ControllerComponents, leagueUserRepo: LeagueUserRepo)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers
  implicit val parser = parse.default

  def getSingleTeamReq(leagueId: String, userId: String) = (new LeagueAction(leagueId) andThen (new LeagueUserAction(userId)).apply()).async { implicit request =>
    Future(inTransaction(Ok(Json.toJson(leagueUserRepo.getCurrentTeam(request.league.id, request.user.id)))))
  }

  def getAllTeamsReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future {
      inTransaction {
        Ok(Json.toJson(request.league.users.associations.map(lu => lu.team.map(_.pickee).toList)))
      }
    }
  }

}
