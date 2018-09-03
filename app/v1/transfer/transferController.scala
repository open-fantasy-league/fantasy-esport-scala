package v1.transfer

import java.sql.Timestamp
import javax.inject.Inject

import entry.SquerylEntrypointForMyApp._
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import models.{AppDB, League, LeagueUser, User}
import utils.IdParser
case class TransferFormInput(buy: Option[List[Int]], sell: Option[List[Int]], isCheck: Boolean)

class TransferController @Inject()(cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val transferForm: Form[TransferFormInput] = {

    Form(
    mapping(
    "buy" -> optional(list(number)),
    "sell" -> optional(list(number)),
    "isCheck" -> boolean
    )(TransferFormInput.apply)(TransferFormInput.unapply)
    )
  }

  // todo add a transfer check call
  def transfer(userId: String, leagueId: String) = Action.async(parse.json) { implicit request =>
    processJsonTransfer(userId, leagueId)
  }

  private def processJsonTransfer[A](userId: String, leagueId: String)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[TransferFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: TransferFormInput) = {
      println("yay")
      // verify leagueUser exists
      // verify doesnt violate remaining transfers
      // verify can afford change
      // verify doesnt break team size lim
      // verify doesnt break faction limit

      Future {
        inTransaction {
          // TODO handle invalid Id
          for {
            userId <- IdParser.parseIntId(userId, "User")
            leagueId <- IdParser.parseIntId(leagueId, "League")
            league <- AppDB.leagueTable.lookup(leagueId).toRight(BadRequest(f"League does not exist: $leagueId"))
          // TODO what does single return if no entries?
            leagueUser <- Try(league.users.where(lu => lu.id === userId).single).toOption.toRight(BadRequest(f"User($userId) not in this league($leagueId)"))
          } yield leagueUser
        }
        Ok("Yer dun fucked up")
      }
      //scala.concurrent.Future{ Ok(views.html.index())}
      //      postResourceHandler.create(input).map { post =>
      //      Created(Json.toJson(post)).withHeaders(LOCATION -> post.link)
      //      }
      // TODO good practice post-redirect-get
    }

    transferForm.bindFromRequest().fold(failure, success)
  }
}