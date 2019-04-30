package v1.admin

import java.sql.Connection
import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, InternalServerError}
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import java.time.LocalDateTime

import anorm._
import anorm.SqlParser.long
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import models._

class AdminExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait AdminRepo{
  def insertApiUser(name: String, email: String, role: Int)(implicit c: Connection): ApiUserRow
}

@Singleton
class AdminRepoImpl @Inject()(implicit ec: AdminExecutionContext) extends AdminRepo{
  override def insertApiUser(name: String, email: String, role: Int)(implicit c: Connection): ApiUserRow = {
    val q = s"""insert into api_user(name, email, role) values ('$name', '$email', $role) returning api_user_id as key, name, email, role;"""
    SQL(q).executeInsert(ApiUserRow.parser.single)
  }
}

