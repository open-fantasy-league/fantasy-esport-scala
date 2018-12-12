package auth

import play.api.mvc._
import play.api.mvc.Result
import play.api.mvc.Results._
import scala.concurrent.{ExecutionContext, Future}
import models.{League, AppDB, Period}
import javax.inject.Inject
import utils.{IdParser, TryHelper}
import entry.SquerylEntrypointForMyApp._
import com.typesafe.config.{Config, ConfigFactory}

class AuthRequest[A](val apiKey: Option[String], request: Request[A]) extends WrappedRequest[A](request)

class AuthAction @Inject()(val parser: BodyParsers.Default)(implicit val executionContext: ExecutionContext)
  extends ActionBuilder[AuthRequest, AnyContent] with ActionTransformer[Request, AuthRequest] {
  def transform[A](request: Request[A]) = Future.successful {
    new AuthRequest(request.getQueryString("apiKey"), request)
  }
}

class AuthLeagueRequest[A](val league: League, request: AuthRequest[A]) extends WrappedRequest[A](request) {
  def apiKey = request.apiKey
}

class LeagueRequest[A](val league: League, request: Request[A]) extends WrappedRequest[A](request)
//class PeriodRequest[A](val period: Option[Int], request: Request[A]) extends WrappedRequest[A](request)
class PeriodRequest[A](val period: Option[Int], request: Request[A]) extends WrappedRequest[A](request)
class LeaguePeriodRequest[A](val period: Option[Int], request: LeagueRequest[A]) extends WrappedRequest[A](request){
  def league = request.league
}

class LeagueAction(val parser: BodyParser[AnyContent], leagueId: String)(implicit val ec: ExecutionContext) extends ActionBuilder[LeagueRequest, AnyContent] with ActionRefiner[Request, LeagueRequest]{
  def executionContext = ec
  override def refine[A](input: Request[A]) = Future.successful {
    inTransaction(
      (for {
        leagueIdLong <- IdParser.parseLongId(leagueId, "league")
        league <- AppDB.leagueTable.lookup(leagueIdLong).toRight(NotFound(f"League id $leagueId does not exist"))
        out <- Right(new LeagueRequest(league, input))
      } yield out)
    )
  }
}

class PeriodAction(val parser: BodyParser[AnyContent])(implicit val ec: ExecutionContext) extends ActionBuilder[PeriodRequest, AnyContent] with ActionRefiner[Request, PeriodRequest]{
  def executionContext = ec
  override def refine[A](input: Request[A]) = Future.successful {
    inTransaction(
      (for {
        period <- TryHelper.tryOrResponse(() => input.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
        out <- Right(new PeriodRequest(period, input))
      } yield out)
    )
  }
}
object LeaguePeriodAction{
  def apply()(implicit ec: ExecutionContext) = new ActionRefiner[LeagueRequest, LeaguePeriodRequest]{
    def executionContext = ec
    def refine[A](input: LeagueRequest[A]) = Future.successful {
      inTransaction(
        (for {
          period <- TryHelper.tryOrResponse(() => input.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          out <- Right(new LeaguePeriodRequest(period, input))
        } yield out)
      )
    }
  }
}


class Auther @Inject(){
  val config = ConfigFactory.load()
  val adminKey = config.getString("adminKey")
  val adminHost = config.getString("adminHost")
  def AuthLeagueAction(leagueId: String)(implicit ec: ExecutionContext) = new ActionRefiner[AuthRequest, AuthLeagueRequest] {
    def executionContext = ec
    def refine[A](input: AuthRequest[A]) = Future.successful {
      inTransaction(
        (for {
          leagueId <- IdParser.parseLongId(leagueId, "league")
          league <- AppDB.leagueTable.lookup(leagueId).toRight(NotFound(f"League id $leagueId does not exist"))
          out <- Right(new AuthLeagueRequest(league, input))
        } yield out)
      )
    }
  }

  def PermissionCheckAction(implicit ec: ExecutionContext) = new ActionFilter[AuthLeagueRequest] {
    def executionContext = ec
    def filter[A](input: AuthLeagueRequest[A]) = Future.successful {
      if (input.league.apiKey != input.apiKey.getOrElse(""))
        Some(Forbidden(f"Must specify correct API key associated with this league, for this request. i.e. v1/leagues/1/startDay?apiKey=AASSSDDD"))
      else
        None
    }
  }

  def AdminCheckAction(implicit ec: ExecutionContext) = new ActionFilter[AuthRequest] {
    def executionContext = ec
    def filter[A](input: AuthRequest[A]) = Future.successful {
      if (adminKey != input.apiKey.getOrElse("") && input.remoteAddress == adminHost)
        Some(Forbidden(f"Only admin can perform this operation"))
      else
        None
    }
  }
}

