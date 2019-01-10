package controllers

import javax.inject.Inject

import play.api.mvc._

/**
  * A very small controller that renders a home page.
  */
class HomeController @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  def index = Action { implicit request =>
    import models._
    import entry.SquerylEntrypointForMyApp._
    inTransaction(
      AppDB.create
    )
    inTransaction{
      AppDB.gameTable.insert(new Game("DotA 2", "DOTA", "Heroes", "Pick a team of dota heroes. Score points when they are picked, banned, or win"))
      AppDB.apiUserTable.insert(new APIUser("Testname", "test email"))
    }

    Ok(views.html.index())
  }
}
