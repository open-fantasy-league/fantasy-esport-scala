package auth

import play.api.mvc._
import play.api.mvc.Result
import play.api.mvc.Results._
import scala.concurrent.{ExecutionContext, Future}
import models._
import javax.inject.Inject
import utils.{IdParser, TryHelper}
import entry.SquerylEntrypointForMyApp._
import com.typesafe.config.{Config, ConfigFactory}

class AuthRequest[A](val apiKey: Option[String], request: Request[A]) extends WrappedRequest[A](request)

class AuthAction()(implicit val executionContext: ExecutionContext, val parser:BodyParser[AnyContent])
  extends ActionBuilder[AuthRequest, AnyContent] with ActionTransformer[Request, AuthRequest] {
  def transform[A](request: Request[A]) = Future.successful {
    new AuthRequest(request.getQueryString("apiKey"), request)
  }
}

class LeagueRequest[A](val league: League, request: Request[A]) extends WrappedRequest[A](request)
class PeriodRequest[A](val period: Option[Int], request: Request[A]) extends WrappedRequest[A](request)

class AuthLeagueRequest[A](val l: League, request: AuthRequest[A]) extends LeagueRequest[A](l, request) {
  def apiKey = request.apiKey
}

class LeaguePeriodRequest[A](val p: Option[Int], request: LeagueRequest[A]) extends PeriodRequest[A](p, request){
  def league = request.league
}

class LeagueUserRequest[A](val user: User, val leagueUser: LeagueUser, request: LeagueRequest[A]) extends WrappedRequest[A](request){
  def league = request.league
}

class AuthLeagueUserRequest[A](val u: User, val lu: LeagueUser, request: AuthLeagueRequest[A]) extends LeagueUserRequest[A](u, lu, request){
  def apiKey = request.apiKey
}

class LeagueAction(leagueId: String)(implicit val ec: ExecutionContext, val parser: BodyParser[AnyContent]) extends ActionBuilder[LeagueRequest, AnyContent] with ActionRefiner[Request, LeagueRequest]{
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

class PeriodAction()(implicit val ec: ExecutionContext, val parser: BodyParser[AnyContent]){
  def executionContext = ec
  def refineGeneric[A <: Request[B], B](input: A): Either[Result, Option[Int]] = {
      inTransaction(
        TryHelper.tryOrResponse(() => input.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
      )
    }

  def apply()(implicit ec: ExecutionContext) = new ActionRefiner[Request, PeriodRequest]{
    def executionContext = ec
    def refine[A](input: Request[A]) = Future.successful{
      refineGeneric[Request[A], A](input).map(p => new PeriodRequest(p, input))
    }

  }
  def league()(implicit ec: ExecutionContext) = new ActionRefiner[LeagueRequest, LeaguePeriodRequest]{
    def executionContext = ec
    def refine[A](input: LeagueRequest[A]) = Future.successful {
      refineGeneric[LeagueRequest[A], A](input).map(p => new LeaguePeriodRequest(p, input))
    }
  }
}
/*object LeaguePeriodAction{
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
}*/
class LeagueUserAction(val userId: String){
  def refineGeneric[A <: LeagueRequest[B], B](input: A): Either[Result, (User, LeagueUser)] = {
      inTransaction(
        (for {
          userIdLong <- IdParser.parseLongId(userId, "User")
          isInternal = !input.getQueryString("internalUserId").isEmpty
          (internalUserId, externalUserId) = isInternal match{
            case true => (Some(userIdLong), None)
            case false => (None, Some(userIdLong))
          }
          query <- TryHelper.tryOrResponse(() => join(AppDB.userTable, AppDB.leagueUserTable.leftOuter)((u, lu) => where((lu.get.leagueId === input.league.id) and (u.externalId === externalUserId.?) and (u.id === internalUserId.?))
            select(u, lu.get)
            on(lu.get.userId === u.id)).single, BadRequest(f"User $userId not in league"))
        } yield query)
      )
    }

  def apply()(implicit ec: ExecutionContext) = new ActionRefiner[LeagueRequest, LeagueUserRequest]{
    def executionContext = ec
    def refine[A](input: LeagueRequest[A]) = Future.successful{
      refineGeneric[LeagueRequest[A], A](input).map(q => new LeagueUserRequest(q._1, q._2, input))
    }

  }
  def auth()(implicit ec: ExecutionContext) = new ActionRefiner[AuthLeagueRequest, AuthLeagueUserRequest]{
    def executionContext = ec
    def refine[A](input: AuthLeagueRequest[A]) = Future.successful {
      refineGeneric[AuthLeagueRequest[A], A](input).map(q => new AuthLeagueUserRequest(q._1, q._2, input))
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

