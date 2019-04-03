package v1.result

import java.sql.Connection
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.libs.json._
import play.api.libs.concurrent.CustomExecutionContext

import anorm._
import anorm.SqlParser.long
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

import models.AppDB._
import models._
import utils.GroupByOrderedImplicit._

class ResultExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class ResultQuery(matchu: MatchRow, resultu: ResultRow, points: PointsRow, statField: LeagueStatFieldRow, pickee: PickeeRow)

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
  def get(period: Option[Int])(implicit c: Connection): Iterable[ResultsOut]
  def resultQueryExtractor(query: Iterable[ResultQuery]): Iterable[ResultsOut]
}

@Singleton
class ResultRepoImpl @Inject()()(implicit ec: ResultExecutionContext) extends ResultRepo{
  val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
  val pickeeParser: RowParser[PickeeRow] = Macro.namedParser[PickeeRow](ColumnNaming.SnakeCase)
  override def show(id: Long): Option[Resultu] = {
    resultTable.lookup(id)
  }

  override def get(period: Option[Int])(implicit c: Connection): Iterable[ResultsOut] = {
    val q =
      """
        | select * from matchu m join resultu r on (m.id = r.matchId)
        | join points p on (p.resultId = r.id)
        | join league_stat_field lsf on (lsf.id = p.pointsFieldId)
        | join pickee pck on (r.pickeeId = pck.id)
        | where m.league_id = {leagueId} and ({period} is null or m.period = {period})
        | order by m.targeted_at_tstamp desc, p.value;
      """.stripMargin
    val r = SQL(q).on("id" -> id).as((MatchRow.parser ~ ResultRow.parser ~ PointsRow.parser ~ lsfParser ~ pickeeParser).*)
    // TODO period filter
    // TODO filter by league
//    val queryRaw = from(matchTable, resultTable, pointsTable, leagueStatFieldTable, pickeeTable)(
//      (m, r, p, s, pck) => where(r.matchId === m.id and p.resultId === r.id and p.pointsFieldId === s.id and r.pickeeId === pck.id and (m.period === period .?))
//      select((m, r, p, s, pck))
//      orderBy(m.targetedAtTstamp desc, p.value asc)
//    )
//    val query = queryRaw.map(q => ResultQuery(q._1, q._2, q._3, q._4, q._5))
    resultQueryExtractor(r)
  }

  override def resultQueryExtractor(query: Iterable[ResultQuery]): Iterable[ResultsOut] = {
    val grouped = query.groupByOrdered(_.matchu)
    grouped.map({case (matchu, v) => 
      val results = v.groupByOrdered(tup => (tup.resultu, tup.pickee)).map({
        case ((resultu, pickee), x) => SingleResult(resultu, pickee, x.map(y => y.statField.name -> y.points.value).toMap)
      })//(collection.breakOut): List[SingleResult]
      ResultsOut(matchu, results)
    })
  }

}

