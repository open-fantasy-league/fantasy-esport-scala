package v1.tasks

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

import javax.inject.Inject
import akka.actor.ActorSystem
import play.api.db.Database
import play.api.libs.concurrent.CustomExecutionContext

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import v1.transfer.TransferRepo



class TasksCustomExecutionContext @Inject()(actorSystem: ActorSystem)
    extends CustomExecutionContext(actorSystem, "backgroundtasks")

class BackgroundDraftTask @Inject()(actorSystem: ActorSystem, transferRepo: TransferRepo)(
  implicit ec: TasksCustomExecutionContext, db: Database){

  run()

  def run(): Unit = {
    val deadlines = db.withConnection { implicit c => transferRepo.getDraftDeadlines() }
    // TODO handle missed ones due to restart
    //deadlines.withFilter(_.nextDraftDeadline.isBefore(LocalDateTime.now())).map()
    deadlines.foreach(d => recursiveSchedule(d.leagueId, d.nextDraftDeadline))
  }

  def recursiveSchedule(leagueId: Long, deadline: LocalDateTime): Unit = {
    println(s"scheduling $leagueId draft check ${deadline.toString}")
    var nextDeadline: Option[LocalDateTime] = None
    actorSystem.scheduler.scheduleOnce(
      FiniteDuration(LocalDateTime.now().getNano - deadline.getNano, TimeUnit.NANOSECONDS)
    ) {
      db.withConnection { implicit c => {nextDeadline = transferRepo.draftDeadlineReached(leagueId)} }
    }
    nextDeadline.foreach(d => recursiveSchedule(leagueId, d))
  }

//  actorSystem.scheduler.schedule(initialDelay = 5.seconds, interval = 4.hours) {
//    process()
//  }

  def process(): Unit = {
    for (_ <- 0 until 100) {
      println("This originally executed 5 minutes after the server started and will execute again in 4 hours")
    }
  }
}