package auth

import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject

class AuthRequest[A](val apikey: Option[String], request: Request[A]) extends WrappedRequest[A](request)

class AuthAction @Inject()(val parser: BodyParsers.Default)(implicit val executionContext: ExecutionContext)
  extends ActionBuilder[AuthRequest, AnyContent] with ActionTransformer[Request, AuthRequest] {
  def transform[A](request: Request[A]) = Future.successful {
    new AuthRequest(request.session.get("apikey"), request)
  }
}

//object AuthAction