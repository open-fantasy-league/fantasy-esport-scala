package v1.result

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import models.AppDB._
import models._
import scala.collection.mutable.ArrayBuffer

class ResultExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class ResultQuery(matchu: Matchu, resultu: Resultu, points: Points, statField: LeagueStatField)

case class SingleResult(result: Resultu, results: Map[String, Double])

case class ResultsOut(matchu: Matchu, results: Iterable[SingleResult])
trait ResultRepo{
  def show(id: Long): Option[Resultu]
  def get(day: Option[Int]): Iterable[ResultsOut]
  def resultQueryExtractor(query: Iterable[ResultQuery]): Iterable[ResultsOut]
}

@Singleton
class ResultRepoImpl @Inject()()(implicit ec: ResultExecutionContext) extends ResultRepo{
  override def show(id: Long): Option[Resultu] = {
    resultTable.lookup(id)
  }

  override def get(day: Option[Int]): Iterable[ResultsOut] = {
    // TODO day filter
    val queryRaw = from(matchTable, resultTable, pointsTable, leagueStatFieldTable)(
      (m, r, p, s) => where(r.matchId === m.id and p.resultId === r.id and p.pointsFieldId === s.id)
      select((m, r, p, s))
//    ).groupBy(_._1).mapValues(_.map(_._2))
    )
    val query = queryRaw.map(q => ResultQuery(q._1, q._2, q._3, q._4))
    //q.groupBy(_._1).mapValues(_.groupBy(_._2).mapValues(_.map(_._3)))
    resultQueryExtractor(query)
  }

  override def resultQueryExtractor(query: Iterable[ResultQuery]): Iterable[ResultsOut] = {
    val grouped = query.groupBy(_.matchu)
    grouped.map({case (matchu, v) => 
      val results = v.groupBy(_.resultu).map({case (resultu, x) => SingleResult(resultu, x.map(y => y.statField.name -> y.points.value).toMap)})//(collection.breakOut): List[SingleResult]
      ResultsOut(matchu, results)
    })
  }

}

