package models

import entry.SquerylEntrypointForMyApp._
import org.squeryl.Schema

object AppDB extends Schema {
  val userTable = table[User]
  val leagueTable = table[League]
  val gameTable = table[Game]
  val passwordResetTable = table[PasswordReset]
  val apiUserTable = table[APIUser]
  val leagueUserStatsTable = table[LeagueUserStats]
  val leagueFactionTable = table[LeagueFaction]
  val leagueStatFieldsTable = table[LeagueStatFields]
  val pickeeTable = table[Pickee]
  val teamPickeeTable = table[TeamPickee]
  val pickeeStatsTable = table[PickeeStats]
  val friendTable = table[Friend]
  val transferTable = table[Transfer]
  val resultTable = table[Resultu]
  val pointsFieldTable = table[PointsField]
  val pointsTable = table[Points]
  val matchTable = table[Matchu]
//  val hallOfFameTable = table[HallOfFame]
//  val userXpTable = table[UserXp]
//  val achievementTable = table[Achievement]
  val notificationTable = table[Notification]
  val leaguePrizeTable = table[LeaguePrize]

  // League User has many to many relation. each user can play in many leagues. each league can have many users
  // TODO this should be true of user achievements as well
  val leagueUserTable =
  manyToManyRelation(leagueTable, userTable).
    via[LeagueUser]((l, u, lu) => (lu.leagueId === l.id, u.id === lu.userId))

//  val userAchievementTable =
//    manyToManyRelation(achievementTable, userTable).
//      via[UserAchievement]((a, u, ua) => (ua.achievementId === a.id, u.id === ua.userId))

  // lets do all our oneToMany foreign keys
  val leagueUserToLeagueUserStats =
    oneToManyRelation(leagueUserTable, leagueUserStatsTable).
      via((lu, lus) => (lu.id === lus.leagueUserId))

  val leagueToLeaguePrize =
    oneToManyRelation(leagueTable, leaguePrizeTable).
      via((l, o) => (l.id === o.leagueId))

  // TODO leagueFaction relation

  val leagueToLeagueStatFields =
    oneToManyRelation(leagueTable, leagueStatFieldsTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToPickee =
    oneToManyRelation(leagueTable, pickeeTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToPointsField =
    oneToManyRelation(leagueTable, pointsFieldTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToMatch =
    oneToManyRelation(leagueTable, matchTable).
      via((l, o) => (l.id === o.leagueId))

//  val leagueToHallOfFame =
//    oneToManyRelation(leagueTable, hallOfFameTable).
//      via((l, o) => (l.id === o.leagueId))

  val pickeeToTeamPickee =
    oneToManyRelation(pickeeTable, teamPickeeTable).
      via((p, o) => (p.id === o.pickeeId))

  val pickeeToPickeeStats =
    oneToManyRelation(pickeeTable, pickeeStatsTable).
      via((p, o) => (p.id === o.pickeeId))

  val pickeeToResult =
    oneToManyRelation(pickeeTable, resultTable).
      via((p, o) => (p.id === o.pickeeId))

  val pickeeToTransfer =
    oneToManyRelation(pickeeTable, transferTable).
      via((p, o) => (p.id === o.pickeeId))

  val leagueUserToTransfer =
    oneToManyRelation(leagueUserTable, transferTable).
      via((lu, o) => (lu.id === o.leagueUserId))

  val userToNotification =
    oneToManyRelation(userTable, notificationTable).
      via((u, o) => (u.id === o.userId))

//  val userToUserXp =
//    oneToManyRelation(userTable, userXpTable).
//      via((u, o) => (u.id === o.userId))

  val userToPasswordReset =
    oneToManyRelation(userTable, passwordResetTable).
      via((u, o) => (u.id === o.userId))

  val userToFriend =
    oneToManyRelation(userTable, friendTable).
      via((u, o) => (u.id === o.userId))

  val userToFriendTwo =
    oneToManyRelation(userTable, friendTable).
      via((u, o) => (u.id === o.friendId))

//  val gameToHallOfFame =
//    oneToManyRelation(gameTable, hallOfFameTable).
//      via((g, o) => (g.id === o.gameId))

  val gameToLeague =
    oneToManyRelation(gameTable, leagueTable).
      via((g, o) => (g.id === o.gameId))

//  val gameToAchievement =
//    oneToManyRelation(gameTable, achievementTable).
//      via((g, o) => (g.id === o.gameId))

  val leagueWithPrize =
    join(leagueTable, leaguePrizeTable.leftOuter)((l, lp) =>
      select((l, lp))
      on(l.id === lp.map(_.leagueId))
    )

//  val fullLeagueInfo =
//    join(leagueTable, leaguePrizeTable.leftOuter)((l, lp) =>
//      select(l, lp)
//        on(l.id === lp.map(_.leagueId))
//    )

  val leagueWithStatFields =
    join(leagueTable, leagueStatFieldsTable.leftOuter)((l, lsf) =>
      select((l, lsf))
        on(l.id === lsf.map(_.leagueId))
    )

//  object LeagueWithStatFields{
//    implicit val implicitWrites = new Writes[League] {
//      def writes(league: League, statFields: List[Option[LeagueStatFields]] = Nil): JsValue = {
//        Json.obj(
//          "id" -> league.id,
//          "name" -> league.name,
//          "gameId" -> league.gameId,
//          "tournamentId" -> league.tournamentId,
//          "isPrivate" -> league.isPrivate,
//          "tournamentId" -> league.tournamentId,
//          "totalDays" -> league.totalDays,
//          "pickee" -> league.pickeeDescription,
//          "dayStart" -> league.dayStart,
//          "dayEnd" -> league.dayEnd,
//          "pointsMultiplier" -> league.pointsMultiplier,
//          "teamSize" -> league.teamSize,
//          //val captain: Boolean,
//          "transferLimit" -> league.transferLimit, // use -1 for no transfer limit I think. only applies after day 1 start
//          "startingMoney" -> league.startingMoney
//        )
//      }
//    }
//  }
}