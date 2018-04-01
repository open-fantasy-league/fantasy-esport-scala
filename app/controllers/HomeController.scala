package controllers

import javax.inject.Inject

import play.api.mvc._
import models._

import org.squeryl.PrimitiveTypeMode._

/**
  * A very small controller that renders a home page.
  */
class HomeController @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  def index = Action { implicit request =>
    inTransaction(
      AppDB.create
    )
    inTransaction(
      AppDB.gameTable.insert(new Game("Dota", "DOTA", "hero"))
    )
//    val name: String,
//    val code: String,
//    var pickee: String,  //i.e. Hero, champion, player
//    var teamSize: Int,
//    var reserveSize: Int,
//    var defaultLeague: Long
    Ok(views.html.index())
  }
}
