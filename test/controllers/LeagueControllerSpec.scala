import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.test._
import play.api.test.Helpers._
import play.api.test.CSRFTokenHelper._

class LeagueControllerSpec extends PlaySpec with GuiceOneAppPerTest {

  "LeagueController" should {

    "show league json" in {
      val request = FakeRequest(GET, "/v1/leagues/1").withHeaders(HOST -> "localhost:9000").withCSRFToken
      val league = route(app, request).get

      contentAsString(league) must include ("This is a placeholder page to show you the REST API.")
    }

  }

}

