package v1.tasks

import java.time.{LocalDateTime, ZoneOffset}

import javax.inject.Inject
import akka.actor.{ActorSystem, Cancellable}
import play.api.db.Database
import play.api.libs.concurrent.CustomExecutionContext
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, InternalServerError}

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

import utils.TryHelper.tryOrResponseRollback
import v1.transfer.{DraftSystem, TransferRepo}



class TasksCustomExecutionContext @Inject()(actorSystem: ActorSystem)
    extends CustomExecutionContext(actorSystem, "backgroundtasks")

class BackgroundDraftTask @Inject()(actorSystem: ActorSystem, transferRepo: TransferRepo)(
  implicit ec: TasksCustomExecutionContext, db: Database){
  private val CHECK_DEADLINE_INTERVAL_SECS = 10
  //TODO what to do about scenario where they move draft back/forward and scheduled wrong.
  // I guess if moved back, numMissed is 0 and nextDeadline has updated so we gucci
  // If moved forward then we've scheduled it twice, but thats fine, 2nd one doesnt do anything like above
  private var scheduledDrafts = Map[Long, (LocalDateTime, Cancellable)]()

  private def scheduleDelay(nextDraftDeadline: LocalDateTime) = {
    val out =
    FiniteDuration(  // +1 as better to be slightly late, than cut off before someone last millisecond clicks
      nextDraftDeadline.toEpochSecond(ZoneOffset.UTC) - LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) + 1,
      TimeUnit.SECONDS)
    println("\n\nFiniteDuration")
    println(out.toString())
    out
  }


  run()

  def run()= {
    actorSystem.scheduler.schedule(initialDelay = 0.seconds, interval = CHECK_DEADLINE_INTERVAL_SECS.seconds){
      val deadlines = db.withConnection { implicit c => transferRepo.getDraftDeadlines()}
      deadlines.foreach(updateSchedule)
    }
  }

  def updateSchedule(draft: DraftSystem) = {
    val currentDraft = scheduledDrafts.get(draft.leagueId)
    currentDraft match{
      case None => {
        val schedule = actorSystem.scheduler.scheduleOnce(scheduleDelay(draft.nextDraftDeadline)){
          db.withTransaction { implicit c => {
            tryOrResponseRollback({transferRepo.draftDeadlineReached(draft.leagueId)}, c, InternalServerError("Its fucked bro"))
            } }
        }
        println("\n\nNew schedule")
        scheduledDrafts = scheduledDrafts + (draft.leagueId -> (draft.nextDraftDeadline, schedule))
      }
      case Some(d) => {
        // Only cancel and refresh schedule if old one out of date
        if (d._1 != draft.nextDraftDeadline){
          d._2.cancel()
          val schedule = actorSystem.scheduler.scheduleOnce(scheduleDelay(draft.nextDraftDeadline)){
            println("why execute now?")
            db.withTransaction { implicit c => {
              tryOrResponseRollback({transferRepo.draftDeadlineReached(draft.leagueId)},
                c, InternalServerError("Its fucked bro"))
            } }
          }
          scheduledDrafts = scheduledDrafts + (draft.leagueId -> (draft.nextDraftDeadline, schedule))
          println("\n\nupdated old schedule")
        }
        else{
          println("\n\ntimes match wtf")
          println(d._1.toString)
        }
      }
    }

  }
}