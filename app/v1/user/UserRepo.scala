package v1.user

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import anorm._
import play.api.db._
import models._
import v1.league.LeagueRepo


class UserExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait UserRepo{
  def get(userId: Long)(implicit c: Connection): Option[UserRow]
  def insert(usernameIn: String, externalUserId: Long)(implicit c: Connection): UserRow
  def update(userId: Long, input: UpdateUserFormInput)(implicit c: Connection): Unit
}

@Singleton
class UserRepoImpl @Inject()()(implicit ec: UserExecutionContext, leagueRepo: LeagueRepo) extends UserRepo{
  override def get(userId: Long)(implicit c: Connection): Option[UserRow] = {
    SQL(s"select user_id, username, external_user_id from useru where external_user_id = $userId").as(UserRow.parser.singleOpt)
  }

  override def insert(usernameIn: String, externalUserId: Long)(implicit c: Connection): UserRow = {
    val q =     SQL(
      s"insert into useru(username, external_user_id) values ('$usernameIn', $externalUserId) returning user_id, username, external_user_id;"
    )//.on("username" -> usernameIn, "externalUserId" -> externalUserId)
    println(q.sql)
    print(q.sql.toString())
    q.executeInsert(UserRow.parser.single)
  }

  override def update(userId: Long, input: UpdateUserFormInput)(implicit c: Connection): Unit = {
    val setString = (input.username, input.externalUserId) match {
      case (Some(username), Some(externalId)) => s"set username = $input.username, external_user_id = $input.externalUserId"
      case (None, Some(externalId)) => s"set external_user_id = $input.externalUserId"
      case (Some(username), None) => s"set username = '$input.username'"
      case (None, None) => ""
    }
    SQL(
      s"update useru $setString where external_user_id = $userId returning user_id, username, external_user_id"
    ).executeUpdate()
    println("todo return stuff")
  }
}

