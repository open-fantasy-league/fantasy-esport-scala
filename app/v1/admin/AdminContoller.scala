package v1.admin

import java.sql.Timestamp
import javax.inject.Inject

import entry.SquerylEntrypointForMyApp._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import models._

class AdminController @Inject()(cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{

  def allProcessTransfersReq() = Action.async { implicit request =>
    Future {
      val currentTime = new Timestamp(System.currentTimeMillis())
      inTransaction {
        val updates = from(AppDB.leagueUserTable)(lu =>
              where(lu.changeTstamp.isNotNull and lu.changeTstamp <= currentTime)
              select(lu)
            ).map(processLeagueUserTransfer)
        Ok("Transfer updates processed")
      }
    }
  }

  def allRolloverPeriodReq() = Action.async { implicit request =>
    // TODO request.remoteAddress
    // // TODO test add leagues, sleep before end transaction, and see how id's turn out
    // Thread.sleep(2000)
    Future {
      val currentTime = new Timestamp(System.currentTimeMillis())
      inTransaction {
        val endedUpdates = from(AppDB.leagueTable, AppDB.periodTable)((l,p) =>
              where(l.currentPeriodId === p.id and p.ended === false and p.end <= currentTime and p.nextPeriodId.isNotNull)
              select(p)
              ).map(p => {p.ended = true; p})
        // todo other end day hooks
        AppDB.periodTable.update(endedUpdates)
        val startedUpdates = from(AppDB.leagueTable, AppDB.periodTable)((l,p) =>
              // looking for period that a) isnt current period, b) isnt old ended period (so must be future period!)
              // and is future period that should have started...so lets start it
              where(not(l.currentPeriodId === p.id) and p.ended === false and p.start <= currentTime)
              select((l, p))
              ).map(t => {
                t._1.currentPeriodId = Some(t._2.id)
                t._1
            })
        // todo other start day hooks
        AppDB.leagueTable.update(startedUpdates)
        Ok("Periods rolled over")
      }
    }
  }

  private def processLeagueUserTransfer(leagueUser: LeagueUser) = {
    // TODO map and filter together
    println("in proc trans")
    val transfers = AppDB.transferTable.where(t => t.processed === false and t.leagueUserId === leagueUser.id)
    AppDB.teamPickeeTable.insert(transfers.filter(_.isBuy).map(t => new TeamPickee(t.pickeeId, t.leagueUserId)))
    AppDB.teamPickeeTable.deleteWhere(tp =>
      (tp.leagueUserId === leagueUser.id) and (tp.pickeeId in transfers.filter(!_.isBuy).map(_.pickeeId))
    )
    AppDB.transferTable.update(transfers.map(t => {
      t.processed = true; t
    }))
    leagueUser.changeTstamp = None
    AppDB.leagueUserTable.update(leagueUser)
  }

}
