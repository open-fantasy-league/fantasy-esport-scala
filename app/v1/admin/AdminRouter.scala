package v1.admin

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class AdminRouter @Inject()(controller: AdminController) extends SimpleRouter {
  val prefix = "/v1/admin"

  override def routes: Routes = {

    case POST(p"/rolloverPeriods") => controller.allRolloverPeriodReq
    case POST(p"/addAPIUser") => controller.addAPIUser("Testname", "test email")
    case POST(p"/fixSilverMissingBonus") => controller.fixSilverMissingBonus()
  }
}
