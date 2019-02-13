package models

import org.squeryl.KeyedEntity
import play.api.libs.json._

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
}
