package models

import entry.SquerylEntrypointForMyApp._
import org.squeryl.Schema

object AppDB extends Schema {
  val userTable = table[User]
  val leagueTable = table[League]
  val gameTable = table[Game]
  val apiUserTable = table[APIUser]
  val leagueUserStatTable = table[LeagueUserStat]
  val leagueUserStatDailyTable = table[LeagueUserStatDaily]
  val leagueStatFieldTable = table[LeagueStatField]
  val periodTable = table[Period]
  val pickeeTable = table[Pickee]
  val factionTypeTable = table[FactionType]
  val factionTable = table[Faction]
  val teamPickeeTable = table[TeamPickee]
  val historicTeamPickeeTable = table[HistoricTeamPickee]
  val pickeeStatTable = table[PickeeStat]
  val pickeeStatDailyTable = table[PickeeStatDaily]
  val transferTable = table[Transfer]
  val resultTable = table[Resultu]
  val pointsTable = table[Points]
  val matchTable = table[Matchu]
  val leaguePrizeTable = table[LeaguePrize]

  // League User has many to many relation. each user can play in many leagues. each league can have many users
  // TODO this should be true of user achievements as well
  val leagueUserTable =
  manyToManyRelation(leagueTable, userTable).
    via[LeagueUser]((l, u, lu) => (lu.leagueId === l.id, u.id === lu.userId))

  val leagueToFactionType =
    oneToManyRelation(leagueTable, factionTypeTable).
      via((l, ft) => (l.id === ft.leagueId))

  val factionTypeToFaction =
    oneToManyRelation(factionTypeTable, factionTable).
      via((ft, f) => (ft.id === f.factionTypeId))

  val pickeeFactionTable =
    manyToManyRelation(pickeeTable, factionTable).
      via[PickeeFaction]((p, f, pf) => (pf.pickeeId === p.id, f.id === pf.factionId))

  // lets do all our oneToMany foreign keys
  val leagueUserToLeagueUserStat =
    oneToManyRelation(leagueUserTable, leagueUserStatTable).
      via((lu, lus) => (lu.id === lus.leagueUserId))

  val leagueUserStatToLeagueUserStatDaily =
    oneToManyRelation(leagueUserStatTable, leagueUserStatDailyTable).
      via((lu, o) => (lu.id === o.leagueUserStatId))

  val leagueToLeaguePrize =
    oneToManyRelation(leagueTable, leaguePrizeTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToLeagueStatField =
    oneToManyRelation(leagueTable, leagueStatFieldTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToPickee =
    oneToManyRelation(leagueTable, pickeeTable).
      via((l, o) => (l.id === o.leagueId))

  val leagueToMatch =
    oneToManyRelation(leagueTable, matchTable).
      via((l, o) => (l.id === o.leagueId))

  val pickeeToTeamPickee =
    oneToManyRelation(pickeeTable, teamPickeeTable).
      via((p, o) => (p.id === o.pickeeId))

  val pickeeToHistoricTeamPickee =
    oneToManyRelation(pickeeTable, historicTeamPickeeTable).
      via((p, o) => (p.id === o.pickeeId))

  val pickeeToPickeeStat =
    oneToManyRelation(pickeeTable, pickeeStatTable).
      via((p, o) => (p.id === o.pickeeId))

  val pickeeStatToPickeeStatDaily =
    oneToManyRelation(pickeeStatTable, pickeeStatDailyTable).
      via((p, o) => (p.id === o.pickeeStatId))

  val pickeeToResult =
    oneToManyRelation(pickeeTable, resultTable).
      via((p, o) => (p.id === o.pickeeId))

  val pickeeToTransfer =
    oneToManyRelation(pickeeTable, transferTable).
      via((p, o) => (p.id === o.pickeeId))

  val leagueUserToTransfer =
    oneToManyRelation(leagueUserTable, transferTable).
      via((lu, o) => (lu.id === o.leagueUserId))

  val leagueUserToTeamPickee =
    oneToManyRelation(leagueUserTable, teamPickeeTable).
      via((lu, o) => (lu.id === o.leagueUserId))

  val resultToPoints =
    oneToManyRelation(resultTable, pointsTable).
      via((r, p) => (r.id === p.resultId))

  val statFieldToPoints =
    oneToManyRelation(leagueStatFieldTable, pointsTable).
      via((s, p) => (s.id === p.pointsFieldId))

  val gameToLeague =
    oneToManyRelation(gameTable, leagueTable).
      via((g, o) => (g.id === o.gameId))

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