package v1.admin

import java.time.LocalDateTime
import javax.inject.Inject

import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import models._
import play.api.db._
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import auth._
import v1.league.LeagueRepo
import v1.transfer.TransferRepo
import v1.user.UserRepo

class AdminController @Inject()(
                                 db: Database, cc: ControllerComponents, leagueRepo: LeagueRepo, transferRepo: TransferRepo,
                                 auther: Auther, adminRepo: AdminRepo, userRepo: UserRepo)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{

  implicit val parser = parse.default

  def allRolloverPeriodReq() = (new AuthAction() andThen auther.AdminCheckAction).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        // hacky way to avoid circular dependency
        implicit val updateHistoricRanksFunc: Long => Unit = userRepo.updateHistoricRanks
        val currentTime = LocalDateTime.now()
        leagueRepo.startPeriods(currentTime)
        leagueRepo.endPeriods(currentTime)
        Ok("Periods rolled over")
      }
    }
  }

  def addAPIUser(name: String, email: String) = (new AuthAction() andThen auther.AdminCheckAction).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        Created(Json.toJson(adminRepo.insertApiUser(name, email, 1)))
      }
    }
  }

}
