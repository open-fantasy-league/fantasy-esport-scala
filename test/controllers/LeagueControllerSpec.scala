import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import org.scalatest.mockito.MockitoSugar
import play.api.test._
import play.api.test.Helpers._
import play.api.mvc.Result
import play.api.libs.json._
import v1.league.{LeagueController, LeagueRepository}

import scala.concurrent.{ExecutionContext, Future}
//import scala.collection.mutable.ArrayBuffer
import org.mockito.Mockito._
import models.{League}

class LeagueControllerSpec extends PlaySpec with MockitoSugar{

  "LeagueController" should {
    val leagueRepo = mock[LeagueRepository]
    val fakeLeague = new League()
    when(leagueRepo.show(1)) thenReturn Some(fakeLeague)
    when(leagueRepo.getStatFields(fakeLeague)) thenReturn Array("wins", "picks", "points")

    when(leagueRepo.show(2)) thenReturn None
    val controller = new LeagueController(Helpers.stubControllerComponents(), leagueRepo)(ExecutionContext.Implicits.global)


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
//
//    "add league should return new league" in {
//      val result: Future[Result] = controller.add().apply(FakeRequest())
//      status(result) mustEqual BAD_REQUEST
//    }
  }

}

