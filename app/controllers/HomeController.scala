package controllers

import javax.inject.Inject

import play.api.mvc._

/**
  * A very small controller that renders a home page.
  */
class HomeController @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  def index = Action { implicit request =>
//    import models._
//    import entry.SquerylEntrypointForMyApp._
//    inTransaction(
//      AppDB.create
//    )
//    inTransaction(
//      AppDB.gameTable.insert(new Game("DotA 2", "DOTA"))
//    )
//
    Ok(views.html.index())
  }
}
