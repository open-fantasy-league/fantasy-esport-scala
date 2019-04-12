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
  def insert(username: String, externalId: Long)(implicit c: Connection): UserRow
  def update(userId: Long, input: UpdateUserFormInput)(implicit c: Connection): UserRow
}

@Singleton
class UserRepoImpl @Inject()()(implicit ec: UserExecutionContext, leagueRepo: LeagueRepo) extends UserRepo{
  override def get(userId: Long)(implicit c: Connection): Iterable[UserRow] = {
    SQL("select * from useru where user_id = {}").onParams(userId).as(UserRow.parser.singleOpt)
  }

  override def insert(username: String, externalId: Long)(implicit c: Connection): UserRow = {
    SQL(
      "insert into useru(username, external_id) values ({},{}) returning user_id, username, external_id"
    ).onParams(username, externalId).executeInsert(UserRow.parser.single)
  }

  override def update(userId: Long, input: UpdateUserFormInput)(implicit c: Connection): UserRow = {
//    SQL(
//      "update useru(username, external_id) values ({},{}) returning user_id, username, external_id"
//    ).onParams(username, externalId).executeInsert(UserRow.parser.single)
    println("todo")
  }
}

