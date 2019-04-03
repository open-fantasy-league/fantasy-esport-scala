package models

import entry.SquerylEntrypointForMyApp._
import org.squeryl.Schema

object AppDB extends Schema {

  override def columnNameFromPropertyName(n: String) = {
    NamingConventionTransforms.snakify(n)
  }

  val userTable = table[User]("useru")
  val leagueTable = table[League]("league")
  val gameTable = table[Game]("game")
  val apiUserTable = table[APIUser]("api_user")
  val leagueUserStatTable = table[LeagueUserStat]("league_user_stat")
  val leagueUserStatDailyTable = table[LeagueUserStatDaily]("league_user_stat_daily")
  val leagueStatFieldTable = table[LeagueStatField]("league_stat_field")
  val periodTable = table[Period]("period")
  val pickeeTable = table[Pickee]("pickee")
  val limitTypeTable = table[LimitType]("limit_type")
  val limitTable = table[Limit]("limit")
  val teamTable = table[Team]("team")
  val teamPickeeTable = table[TeamPickee]("team_pickee")
  val pickeeStatTable = table[PickeeStat]("pickee_stat")
  val pickeeStatDailyTable = table[PickeeStatDaily]("pickee_stat_daily")
  val transferTable = table[Transfer]("transfer")
  val resultTable = table[Resultu]("resultu")
  val pointsTable = table[Points]("points")
  val matchTable = table[Matchu]("matchu")
  val leaguePrizeTable = table[LeaguePrize]("league_prize")

  // League User has many to many relation. each user can play in many leagues. each league can have many users
  val leagueUserTable =
  manyToManyRelation(leagueTable, userTable, "league_user").
    via[LeagueUser]((l, u, lu) => (lu.leagueId === l.id, u.id === lu.userId))

  // although you can argue a limit can be applicable to multiple leagues
  // the way limits are used to limit pickees, differently for different leagues,
  // means each identically named limit type needs to be treated separately for different leagues
  // (i.e. when you update limit limit to say 'can only have 2, not 3, players from team A for league 1202,
  // league 1201 shouldnt suddenly change limits)
  val leagueToLimitType =
    oneToManyRelation(leagueTable, limitTypeTable).
      via((l, ft) => l.id === ft.leagueId)

  val limitTypeToLimit =
    oneToManyRelation(limitTypeTable, limitTable).
      via((ft, o) => ft.id === o.limitTypeId)

  val pickeeLimitTable =
    manyToManyRelation(pickeeTable, limitTable, "pickee_limit").
      via[PickeeLimit]((p, f, pf) => (pf.pickeeId === p.id, f.id === pf.limitId))

  // lets do all our oneToMany foreign keys
  val leagueUserToLeagueUserStat =
    oneToManyRelation(leagueUserTable, leagueUserStatTable).
      via((lu, lus) => lu.id === lus.leagueUserId)

  val leagueUserStatToLeagueUserStatDaily =
    oneToManyRelation(leagueUserStatTable, leagueUserStatDailyTable).
      via((lu, o) => lu.id === o.leagueUserStatId)

  val leagueToLeaguePrize =
    oneToManyRelation(leagueTable, leaguePrizeTable).
      via((l, o) => l.id === o.leagueId)

  val apiUserToLeague =
    oneToManyRelation(apiUserTable, leagueTable).
      via((a, l) => a.id === l.apiKey)

  val leagueToLeagueStatField =
    oneToManyRelation(leagueTable, leagueStatFieldTable).
      via((l, o) => l.id === o.leagueId)

  val leagueToPickee =
    oneToManyRelation(leagueTable, pickeeTable).
      via((l, o) => l.id === o.leagueId)

  val leagueToPeriod =
    oneToManyRelation(leagueTable, periodTable).
      via((l, o) => l.id === o.leagueId)

  val leagueToMatch =
    oneToManyRelation(leagueTable, matchTable).
      via((l, o) => l.id === o.leagueId)

  val pickeeToTeamPickee =
    oneToManyRelation(pickeeTable, teamPickeeTable).
      via((p, o) => p.id === o.pickeeId)

  val pickeeToPickeeStat =
    oneToManyRelation(pickeeTable, pickeeStatTable).
      via((p, o) => p.id === o.pickeeId)

  val pickeeStatToPickeeStatDaily =
    oneToManyRelation(pickeeStatTable, pickeeStatDailyTable).
      via((p, o) => p.id === o.pickeeStatId)

  val pickeeToResult =
    oneToManyRelation(pickeeTable, resultTable).
      via((p, o) => p.id === o.pickeeId)

  val pickeeToTransfer =
    oneToManyRelation(pickeeTable, transferTable).
      via((p, o) => p.id === o.pickeeId)

  val leagueUserToTransfer =
    oneToManyRelation(leagueUserTable, transferTable).
      via((lu, o) => lu.id === o.leagueUserId)

  val leagueUserToTeam =
    oneToManyRelation(leagueUserTable, teamTable).
      via((lu, o) => lu.id === o.leagueUserId)

  val teamToTeamPickee =
    oneToManyRelation(teamTable, teamPickeeTable).
      via((t, o) => t.id === o.teamId)

  val resultToPoints =
    oneToManyRelation(resultTable, pointsTable).
      via((r, p) => r.id === p.resultId)

  val statFieldToPoints =
    oneToManyRelation(leagueStatFieldTable, pointsTable).
      via((s, p) => s.id === p.pointsFieldId)

  val gameToLeague =
    oneToManyRelation(gameTable, leagueTable).
      via((g, o) => g.id === o.gameId)

  val leagueWithPrize =
    join(leagueTable, leaguePrizeTable.leftOuter)((l, lp) =>
      select((l, lp))
      on(l.id === lp.map(_.leagueId))
    )

  val leagueWithStatFields =
    join(leagueTable, leagueStatFieldTable.leftOuter)((l, lsf) =>
      select((l, lsf))
        on(l.id === lsf.map(_.leagueId))
    )
}
