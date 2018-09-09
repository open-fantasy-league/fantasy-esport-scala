import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import org.scalatest.mockito.MockitoSugar
import play.api.test._
import play.api.test.Helpers._
import play.api.mvc.Result
import v1.league.{LeagueController, LeagueRepository}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable.ArrayBuffer
import org.mockito.Mockito._
import models.{League}

class LeagueControllerSpec extends PlaySpec with MockitoSugar{

  "LeagueController" should {
    val leagueRepo = mock[LeagueRepository]
    val fakeLeague = new League()
    when(leagueRepo.show(1)) thenReturn Some(fakeLeague)
    when(leagueRepo.getStatFields(fakeLeague)) thenReturn ArrayBuffer("wins", "picks", "points")

    "should be valid" in {
      val controller = new LeagueController(Helpers.stubControllerComponents(), leagueRepo)(ExecutionContext.Implicits.global)
      val result: Future[Result] = controller.show("1").apply(FakeRequest())
      val bodyText: String = contentAsString(result)
      bodyText mustBe "ok"
    }
  }

}

