package v1.result

import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.json._
import play.api.libs.concurrent.CustomExecutionContext

import models.AppDB._
import models._
import utils.GroupByOrderedImplicit._

class ResultExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class ResultQuery(matchu: Matchu, resultu: Resultu, points: Points, statField: LeagueStatField, pickee: Pickee)

case class SingleResult(result: Resultu, pickee: Pickee, results: Map[String, Double])
object SingleResult{
  implicit val implicitWrites = new Writes[SingleResult]{
    def writes(r: SingleResult): JsValue = {
      Json.obj(
        "isTeamOne" -> r.result.isTeamOne,
        "pickee" -> r.pickee,
        "stats" -> r.results,
      )
    }
  }
}

case class ResultsOut(matchu: Matchu, results: Iterable[SingleResult])
object ResultsOut{
  implicit val implicitWrites = new Writes[ResultsOut] {
    def writes(r: ResultsOut): JsValue = {
      Json.obj(
        "match" -> r.matchu,
        "results" -> r.results,
      )
    }
  }
}


trait ResultRepo{
  def show(id: Long): Option[Resultu]
  def get(period: Option[Int]): Iterable[ResultsOut]
  def resultQueryExtractor(query: Iterable[ResultQuery]): Iterable[ResultsOut]
}

@Singleton
class ResultRepoImpl @Inject()()(implicit ec: ResultExecutionContext) extends ResultRepo{
  override def show(id: Long): Option[Resultu] = {
    resultTable.lookup(id)
  }

  override def get(period: Option[Int]): Iterable[ResultsOut] = {
    // TODO period filter
    // TODO filter by league
    // TODO i hardcoded dreamleague here
    val queryRaw = from(matchTable, resultTable, pointsTable, leagueStatFieldTable, pickeeTable)(
      (m, r, p, s, pck) => where(r.matchId === m.id and m.leagueId === 2 and p.resultId === r.id and p.pointsFieldId === s.id and r.pickeeId === pck.id and (m.period === period .?))
      select((m, r, p, s, pck))
      orderBy(m.targetedAtTstamp desc, p.value asc)
    )
    val query = queryRaw.map(q => ResultQuery(q._1, q._2, q._3, q._4, q._5))
    resultQueryExtractor(query)
  }

  override def resultQueryExtractor(query: Iterable[ResultQuery]): Iterable[ResultsOut] = {
    val grouped = query.groupByOrdered(_.matchu)
    grouped.map({case (matchu, v) => 
      val results = v.groupByOrdered(tup => (tup.resultu, tup.pickee)).map({case ((resultu, pickee), x) => SingleResult(resultu, pickee, x.map(y => y.statField.name -> y.points.value).toMap)})//(collection.breakOut): List[SingleResult]
      ResultsOut(matchu, results)
    })
  }

}

