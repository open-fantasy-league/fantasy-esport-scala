package auth

import play.api.mvc._
import play.api.mvc.Result
import play.api.mvc.Results._
import scala.concurrent.{ExecutionContext, Future}
import models._
import javax.inject.Inject
import utils.IdParser
import com.typesafe.config.ConfigFactory
import v1.league.LeagueRepo
import v1.user.UserRepo
import play.api.db._

class AuthRequest[A](val apiKey: Option[String], request: Request[A]) extends WrappedRequest[A](request)

class AuthAction()(implicit val executionContext: ExecutionContext, val parser:BodyParser[AnyContent])
  extends ActionBuilder[AuthRequest, AnyContent] with ActionTransformer[Request, AuthRequest] {
  def transform[A](request: Request[A]) = Future.successful {
    new AuthRequest(request.headers.get("apiKey").orElse(request.getQueryString("apiKey")), request)
  }
}

class LeagueRequest[A](val league: LeagueRow, request: Request[A]) extends WrappedRequest[A](request)
class PeriodRequest[A](val period: Option[Int], request: Request[A]) extends WrappedRequest[A](request)

class AuthLeagueRequest[A](val l: LeagueRow, request: AuthRequest[A]) extends LeagueRequest[A](l, request) {
  def apiKey = request.apiKey
}

class LeaguePeriodRequest[A](val p: Option[Int], request: LeagueRequest[A]) extends PeriodRequest[A](p, request){
  def league = request.league
}

class UserRequest[A](val user: UserRow, request: LeagueRequest[A]) extends WrappedRequest[A](request){
  def league = request.league
}

class AuthUserRequest[A](val u: UserRow, request: AuthLeagueRequest[A]) extends UserRequest[A](u, request){
  def apiKey = request.apiKey
}

class LeagueAction(leagueId: String)(implicit val ec: ExecutionContext, db: Database, leagueRepo: LeagueRepo, val parser: BodyParser[AnyContent])
  extends ActionBuilder[LeagueRequest, AnyContent] with ActionRefiner[Request, LeagueRequest]{
  def executionContext = ec
  override def refine[A](input: Request[A]) = Future.successful {
    db.withConnection { implicit c =>
      for {
        leagueIdLong <- IdParser.parseLongId(leagueId, "league")
        league <- leagueRepo.get(leagueIdLong).toRight(NotFound(f"League id $leagueId does not exist"))
        out <- Right(new LeagueRequest(league, input))
      } yield out
    }
  }
}

//class PeriodAction()(implicit val ec: ExecutionContext, val parser: BodyParser[AnyContent]){
//  def executionContext = ec
//  def refineGeneric[A <: Request[B], B](input: A): Either[Result, Option[Int]] = {
//      TryHelper.tryOrResponse(input.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
//    }
//
//  def apply()(implicit ec: ExecutionContext) = new ActionRefiner[Request, PeriodRequest]{
//    def executionContext = ec
//    def refine[A](input: Request[A]) = Future.successful{
//      refineGeneric[Request[A], A](input).map(p => new PeriodRequest(p, input))
//    }
//
//  }
//  def league()(implicit ec: ExecutionContext) = new ActionRefiner[LeagueRequest, LeaguePeriodRequest]{
//    def executionContext = ec
//    def refine[A](input: LeagueRequest[A]) = Future.successful {
//      refineGeneric[LeagueRequest[A], A](input).map(p => new LeaguePeriodRequest(p, input))
//    }
//  }
//}

class UserAction(userRepo: UserRepo, db: Database)(val userId: String){

  def refineGeneric[A <: LeagueRequest[B], B](input: A): Either[Result, UserRow] = {
      println(userId)
      db.withConnection { implicit c =>
        for {
          userIdLong <- IdParser.parseLongId(userId, "User")
          maybeUser = userRepo.get(input.league.leagueId, userIdLong)
          out <- maybeUser match {
            case Some(user) => Right(user)
            case _ => Left(BadRequest(f"User $userId not in league"))
          }
        } yield out
      }
    }

  def apply()(implicit ec: ExecutionContext) = new ActionRefiner[LeagueRequest, UserRequest]{
    def executionContext = ec
    def refine[A](input: LeagueRequest[A]) = Future.successful{
      refineGeneric[LeagueRequest[A], A](input).map(q => new UserRequest(q, input))
    }
  }

  def auth()(implicit ec: ExecutionContext) = new ActionRefiner[AuthLeagueRequest, AuthUserRequest]{
    def executionContext = ec
    def refine[A](input: AuthLeagueRequest[A]) = Future.successful {
      refineGeneric[AuthLeagueRequest[A], A](input).map(q => new AuthUserRequest(q, input))
    }
  }
}


class Auther @Inject()(leagueRepo: LeagueRepo, db: Database){
  val conf = ConfigFactory.load()
  lazy val adminKey = conf.getString("adminKey")
  lazy val adminHost = conf.getString("adminHost")

  def AuthLeagueAction(leagueId: String)(implicit ec: ExecutionContext) = new ActionRefiner[AuthRequest, AuthLeagueRequest] {
    def executionContext = ec
    def refine[A](input: AuthRequest[A]) = Future.successful {
        db.withConnection { implicit c =>
          (for {
            leagueId <- IdParser.parseLongId(leagueId, "league")
            league <- leagueRepo.get(leagueId).toRight(NotFound(f"League id $leagueId does not exist"))
            out <- Right(new AuthLeagueRequest(league, input))
          } yield out)
        }
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

