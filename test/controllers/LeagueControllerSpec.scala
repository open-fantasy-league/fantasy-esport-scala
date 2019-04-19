import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import org.scalatest.mockito.MockitoSugar
import play.api.test._
import play.api.test.Helpers._
import play.api.mvc.Result
import play.api.mvc.ControllerComponents
import play.api.libs.json._
import v1.league.{LeagueController, LeagueRepo}
import akka.stream.{ActorMaterializer, Materializer}
import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future
//import scala.collection.mutable.ArrayBuffer
import org.mockito.Mockito._
import models.{League}

class LeagueControllerSpec extends PlaySpec with MockitoSugar{
  //implicit lazy val materializer: Materializer = app.materializer

  "LeagueController" should {
    val leagueRepo = mock[LeagueRepo]
    val fakeLeague = new League()
    when(leagueRepo.show(1)) thenReturn Some(fakeLeague)
    when(leagueRepo.getStatFields(fakeLeague)) thenReturn Array("wins", "picks", "points")

    when(leagueRepo.show(2)) thenReturn None
    val controller = new LeagueController(Helpers.stubControllerComponents(), leagueRepo)

    "matching leagueId league should exist" in {
      val result: Future[Result] = controller.show("1").apply(FakeRequest())
      val bodyJson: JsValue = contentAsJson(result)
      status(result) mustEqual OK
      bodyJson("statFields") mustEqual Json.toJson(Seq("wins", "picks", "points"))
    }

    "unmatching leagueId league should return not found" in {
      val result: Future[Result] = controller.show("2").apply(FakeRequest())
      status(result) mustEqual NOT_FOUND
    }

    "non-int leagueId should return error" in {
      val result: Future[Result] = controller.show("one").apply(FakeRequest())
      status(result) mustEqual BAD_REQUEST
    }

    "add league should return new league" in {
//      val action: EssentialAction = Action { request =>
//        val value = (request.body.asJson.get \ "field").as[String]
//        Ok(value)
//      }
      implicit val system = ActorSystem("Test")
      implicit lazy val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext = scala.concurrent.ExecutionContext.Implicits.global
      val request = FakeRequest(POST, "/").withJsonBody(Json.parse(
        s"""{"name": "cat3", "isPrivate": true, "tournamentId": 5401, "totalDays": 5, "dayStart": 1122, "dayEnd": 113345,
          | "transferOpen": false, "gameId": 1, "pickeeDescription": "Hero", "pickees":
          |  [{"id": 1, "name": "dog", "value": 20.0, "limit": "animal"}], "limitLimit": 2,
          |   "limitDescription": "Team", "extraStats": ["wins", "picks", "bans"]}""".stripMargin
      ))
      //val request = FakeRequest(POST)
      //val result = call(action, request)
      val result: Future[Result] = call(controller.add, request)
      //val result: Future[Result] = call(controller.add, request)
      status(result) mustEqual CREATED
    }
  }

}

