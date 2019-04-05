package models

import org.squeryl.KeyedEntity
import play.api.libs.json._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class ResultRow(id: Long, matchId: Long, isTeamOne: Boolean)

class Resultu(  // Result is play/scala keyword. renaming makes things simpler/more obvious
              val matchId: Long,
              val pickeeId: Long,
              var isTeamOne: Boolean, // for showing results
              // maybe want a field that stores points for results.
              // rather than having to sum points matches every time want to show match results.
            ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val pickee = AppDB.pickeeToResult.right(this)
  lazy val points = AppDB.resultToPoints.left(this)
}

object Resultu{
  implicit val implicitWrites = new Writes[Resultu] {
    def writes(r: Resultu): JsValue = {
      Json.obj(
        "id" -> r.matchId,
        "pickee" -> r.pickee.single
      )
    }
  }

  val parser: RowParser[ResultRow] = Macro.namedParser[ResultRow](ColumnNaming.SnakeCase)
}

object ResultRow{
  implicit val implicitWrites = new Writes[ResultRow] {
    def writes(r: ResultRow): JsValue = {
      Json.obj(
        "id" -> r.matchId, // TODO fix
        "isTeamOne" -> r.isTeamOne,
        "pickee" -> "cat"//r.pickee.single
      )
    }
  }

  val parser: RowParser[ResultRow] = Macro.namedParser[ResultRow](ColumnNaming.SnakeCase)
}
