import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.test._
import play.api.test.Helpers._
import play.api.test.CSRFTokenHelper._
import v1.league.LeagueController
import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc.Result

class LeagueControllerSpec extends PlaySpec with GuiceOneAppPerTest {

  "LeagueController" should {

    "should be valid" in {
      val controller = new LeagueController(Helpers.stubControllerComponents())(ExecutionContext.Implicits.global)
      val result: Future[Result] = controller.show("1").apply(FakeRequest())
      val bodyText: String = contentAsString(result)
      bodyText mustBe "ok"
    }
  }

}

