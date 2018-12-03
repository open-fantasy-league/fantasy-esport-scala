package models

import org.squeryl.KeyedEntity
import java.sql.Timestamp
import org.joda.time.DateTime
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

class Points(
              val resultId: Long,
              val pointsFieldId: Long,
              var value: Double
            ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val result = AppDB.resultToPoints.right(this)
  lazy val statField = AppDB.statFieldToPoints.right(this)
}

object Points{
  implicit val implicitWrites = new Writes[Points] {
    def writes(p: Points): JsValue = {
      Json.obj(
        p.statField.single.name -> p.value
      )
    }
  }
}

class Matchu( // because match is an sql keyword
              val leagueId: Int,
              val externalId: Long, // this is the dota2 match id field
              // we dont want to have 2 different games where they can overlap primary key. so dont use match id as primary key
              val period: Int,
              var tournamentId: Int, // for displaying link to tournament page. tournament can differ from league
              var teamOne: String,
              var teamTwo: String,
              var teamOneVictory: Boolean,
              val startTstamp: Timestamp,
              val addedTstamp: Timestamp,
            )
  extends KeyedEntity[Long] {
  val id: Long = 0
}

object Matchu{
  implicit val implicitWrites = new Writes[Matchu] {
    def writes(m: Matchu): JsValue = {
      Json.obj(
        "id" -> m.id,
        // TODO should these come out as long numbers or strings?
        "startTime" -> m.startTstamp,
        "addedTime" -> m.addedTstamp,
        "tournamentId" -> m.tournamentId,
        "externalId" -> m.externalId,
        "teamOne" -> m.teamOne,
        "teamTwo" -> m.teamTwo,
        "teamOneVictory" -> m.teamOneVictory,
      )
    }
  }
}
