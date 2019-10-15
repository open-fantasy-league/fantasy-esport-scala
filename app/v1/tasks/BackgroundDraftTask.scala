package v1.tasks

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

import javax.inject.Inject
import akka.actor.{ActorSystem, Cancellable}
import play.api.db.Database
import play.api.libs.concurrent.CustomExecutionContext

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

import v1.transfer.{DraftSystem, TransferRepo}



class TasksCustomExecutionContext @Inject()(actorSystem: ActorSystem)
    extends CustomExecutionContext(actorSystem, "backgroundtasks")

class BackgroundDraftTask @Inject()(actorSystem: ActorSystem, transferRepo: TransferRepo)(
  implicit ec: TasksCustomExecutionContext, db: Database){
  //TODO what to do about scenario where they move draft back/forward and scheduled wrong.
  // I guess if moved back, numMissed is 0 and nextDeadline has updated so we gucci
  // If moved forward then we've scheduled it twice, but thats fine, 2nd one doesnt do anything like above
  private var scheduledDrafts = Map[Long, (LocalDateTime, Cancellable)]()


  run()

  def run()= {
    actorSystem.scheduler.schedule(initialDelay = 0.seconds, interval = 10.seconds){
      val deadlines = db.withConnection { implicit c => transferRepo.getDraftDeadlines()}
      deadlines.foreach(updateSchedule)
    }
  }

  def updateSchedule(draft: DraftSystem) = {
    val currentDraft = scheduledDrafts.get(draft.leagueId)
    currentDraft match{
      case None => {
        val schedule = actorSystem.scheduler.scheduleOnce(
          FiniteDuration(LocalDateTime.now().getNano - draft.nextDraftDeadline.getNano, TimeUnit.NANOSECONDS)
        ) {
          db.withConnection { implicit c => {transferRepo.draftDeadlineReached(draft.leagueId)} }
        }
        scheduledDrafts = scheduledDrafts + (draft.leagueId -> (draft.nextDraftDeadline, schedule))
      }
      case Some(d) => {
        // Only cancel and refresh schedule if old one out of date
        if (d._1 != draft.nextDraftDeadline){
          d._2.cancel()
          val schedule = actorSystem.scheduler.scheduleOnce(
            FiniteDuration(LocalDateTime.now().getNano - draft.nextDraftDeadline.getNano, TimeUnit.NANOSECONDS)
          ) {
            db.withConnection { implicit c => {transferRepo.draftDeadlineReached(draft.leagueId)} }
          }
          scheduledDrafts = scheduledDrafts + (draft.leagueId -> (draft.nextDraftDeadline, schedule))
        }
      }
    }

  }
}