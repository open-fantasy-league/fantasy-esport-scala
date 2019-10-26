package v1.admin

import java.time.LocalDateTime

import javax.inject.Inject
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import play.api.db._
import anorm._
import auth._
import v1.league.LeagueRepo
import v1.transfer.TransferRepo
import v1.pickee.PickeeRepo

class AdminController @Inject()(
                                 db: Database, cc: ControllerComponents, leagueRepo: LeagueRepo, transferRepo: TransferRepo,
                                 auther: Auther, adminRepo: AdminRepo, pickeeRepo: PickeeRepo)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{

  implicit val parser = parse.default

  def allRolloverPeriodReq() = (new AuthAction() andThen auther.AdminCheckAction).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        implicit val processWaiverPickeesFunc: (Long, Int, Int) => Unit = transferRepo.processWaiverPickees
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

  def fixSilverMissingBonus() = (new AuthAction() andThen auther.AdminCheckAction).async { implicit request =>
    Future {
      val rnd = scala.util.Random
      db.withConnection { implicit c =>
        val cardAndPickeeIds =
          SQL"""select card_id, pickee_id from card left join card_bonus_multiplier using(card_id) where colour = 'SILVER'
            and card_bonus_multiplier_id is NULL;""".as(
            (SqlParser.long("card_id") ~ SqlParser.long("pickee_id")
            ).*)
        cardAndPickeeIds.foreach({case (cardId ~ pickeeId) => {
          val statFieldIds = leagueRepo.getScoringStatFieldsForPickee(5, pickeeId).map(_.statFieldId).toArray
          var randomStatFieldIds = scala.collection.mutable.Set[Long]()
          while (randomStatFieldIds.size < 2) {
            randomStatFieldIds += statFieldIds(rnd.nextInt(statFieldIds.length)) // TODO check this +1
          }
          randomStatFieldIds.foreach(sfid => {
            val isNegative = leagueRepo.getPointsForStat(sfid, pickeeRepo.getPickeeLimitIds(pickeeId)).get < 0.0
            // leads to random from 1.05, 1.10, 1.15 or x0.4 0.6 0.8 for negative
            val multiplier = if (isNegative) (rnd.nextInt(3) + 2).toDouble * 0.2 else {
              (((rnd.nextInt(3) + 1) * 5).toDouble / 100.0) + 1.0
            }
            transferRepo.insertCardBonus(cardId, sfid, multiplier)})
          }})
      }
      Ok("alrighty then")
    }
  }

}
