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
  val factionTypeTable = table[FactionType]("faction_type")
  val factionTable = table[Faction]("faction")
  val teamTable = table[Team]("team")
  val teamPickeeTable = table[TeamPickee]("team_pickee")
  val historicTeamPickeeTable = table[HistoricTeamPickee]("historic_team_pickee")
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

  // although you can argue a faction can be applicable to multiple leagues
  // the way factions are used to limit pickees, differently for different leagues,
  // means each identically named faction type needs to be treated separately for different leagues
  // (i.e. when you update faction limit to say 'can only have 2, not 3, players from team A for league 1202,
  // league 1201 shouldnt suddenly change limits)
  val leagueToFactionType =
    oneToManyRelation(leagueTable, factionTypeTable).
      via((l, ft) => l.id === ft.leagueId)

  val factionTypeToFaction =
    oneToManyRelation(factionTypeTable, factionTable).
      via((ft, o) => ft.id === o.factionTypeId)

  val pickeeFactionTable =
    manyToManyRelation(pickeeTable, factionTable, "pickee_faction").
      via[PickeeFaction]((p, f, pf) => (pf.pickeeId === p.id, f.id === pf.factionId))

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

  val pickeeToHistoricTeamPickee =
    oneToManyRelation(pickeeTable, historicTeamPickeeTable).
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
  //is(unique,indexed("idxEmailAddresses")) compositeKey(courseId, professorId) on(authors)(s => declare(
  //   columns(s.firstName, s.lastName) are unique
  //))
  on(userTable)(t => declare(columns(t.externalId, t.username) are indexed))
  on(leagueTable)(t => declare(columns(t.isPrivate, t.gameId) are indexed))
  on(periodTable)(t => {
    declare(columns(t.nextPeriodId, t.value, t.leagueId) are indexed)
    declare(columns(t.value, t.leagueId) are unique)
  })
  on(leaguePrizeTable)(t => declare(t.leagueId is indexed))
  on(factionTypeTable)(t => {
    declare(columns(t.leagueId, t.name) are indexed)
    declare(columns(t.leagueId, t.name) are unique)
  })
  on(factionTable)(t => {
    declare(columns(t.factionTypeId, t.name) are indexed)
    declare(columns(t.factionTypeId, t.name) are unique)
  })
  on(leagueUserTable)(t => {
    declare(columns(t.changeTstamp, t.leagueId, t.userId) are indexed)
    declare(columns(t.leagueId, t.userId) are unique)
  })
  on(leagueStatFieldTable)(t => {
    declare(columns(t.leagueId, t.name) are indexed)
    declare(columns(t.leagueId, t.name) are unique)
  })
  on(leagueUserStatTable)(t => {
    declare(columns(t.statFieldId, t.leagueUserId) are indexed)
    declare(columns(t.statFieldId, t.leagueUserId) are unique)
  })
  on(leagueUserStatDailyTable)(t => {
    declare(columns(t.period, t.leagueUserStatId) are indexed)
    declare(columns(t.period, t.leagueUserStatId) are unique)
  })
  on(pickeeTable)(t => {
    declare(columns(t.externalId, t.leagueId, t.name) are indexed)
    declare(columns(t.externalId, t.leagueId) are unique)
    declare(columns(t.name, t.leagueId) are unique)
  })
  on(teamTable)(t => {
    declare(columns(t.leagueUserId, t.started, t.ended) are indexed)
    // use fancy constaint for time-range
    declare(columns(t.leagueUserId, t.started) are unique)
    declare(columns(t.leagueUserId, t.ended) are unique)
  })
  on(teamPickeeTable)(t => {
    declare(columns(t.teamId, t.pickeeId) are indexed)
    declare(columns(t.teamId, t.pickeeId) are unique)
  })
  on(historicTeamPickeeTable)(t => {
    declare(columns(t.leagueUserId, t.pickeeId, t.period) are indexed)
    declare(columns(t.leagueUserId, t.pickeeId, t.period) are unique)
  })
  on(pickeeStatTable)(t => {
    declare(columns(t.statFieldId, t.pickeeId) are indexed)
    declare(columns(t.statFieldId, t.pickeeId) are unique)
  })
  on(pickeeStatDailyTable)(t => {
    declare(columns(t.period, t.pickeeStatId) are indexed)
    declare(columns(t.period, t.pickeeStatId) are unique)

  })
  on(pickeeFactionTable)(t => {
    declare(columns(t.factionId, t.pickeeId) are indexed)
    declare(columns(t.factionId, t.pickeeId) are unique)
  })
  on(resultTable)(t => {
    declare(columns(t.pickeeId, t.matchId) are indexed)
    declare(columns(t.pickeeId, t.matchId) are unique)
  })
  on(pointsTable)(t => {
    declare(columns(t.resultId, t.pointsFieldId) are indexed)
    declare(columns(t.resultId, t.pointsFieldId) are unique)
  })
  on(matchTable)(t => {
    declare(columns(t.leagueId, t.externalId, t.period) are indexed)
    declare(columns(t.externalId, t.leagueId) are unique)
  })
  on(transferTable)(t => {
    declare(columns(t.leagueUserId, t.pickeeId, t.scheduledFor, t.processed) are indexed)
  })
}
