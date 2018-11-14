package v1.result

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models.AppDB._
import models.{Resultu, Matchu, Points}
import scala.collection.mutable.ArrayBuffer

class ResultExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait ResultRepo{
  def show(id: Long): Option[Resultu]
  def get(day: Option[Int]): Map[Matchu, Map[Resultu, Iterable[Points]]]
}

@Singleton
class ResultRepoImpl @Inject()()(implicit ec: ResultExecutionContext) extends ResultRepo{
  override def show(id: Long): Option[Resultu] = {
    resultTable.lookup(id)
  }

  override def get(day: Option[Int]): Map[Matchu, Map[Resultu, Iterable[Points]]] = {
    // TODO day filter
    from(matchTable, resultTable, pointsTable)(
      (m, r, p) => where(r.matchId === m.id and p.resultId === r.id)
      select((m, r, p))
//    ).groupBy(_._1).mapValues(_.map(_._2))
    ).groupBy(_._1).mapValues(_.groupBy(_._2).mapValues(_.map(_._3)))
  }

}

