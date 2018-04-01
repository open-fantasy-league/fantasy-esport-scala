package models

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.{Schema, KeyedEntity}
import org.squeryl.dsl.{CompositeKey2}
import java.sql.Timestamp

class User(
            var username: String,
            var password: String,
            var email: Option[String],
            var contactable: Boolean
          ) extends KeyedEntity[Int] {
  val id: Int = 0

  // If a class has an Option[] field, it becomes mandatory
  // to implement a zero argument constructor
  def this() = this("", "", Some(""), false)

  lazy val leagues = AppDB.leagueUserTable.right(this)
}

class APIUser(
               var name: String,
               var apikey: String,
               var email: String,
               var role: Int
             ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class Game(
            val name: String,
            val code: String,
            var pickee: String, //i.e. Hero, champion, player
          ) extends KeyedEntity[Int] {
  val id: Int = 0
}

class League(
              var name: String,
              val game: Int,
              var tournamentId: Int,
              var totalDays: Int,
              val teamSize: Int,
              val reserveSize: Int,
              val captain: Boolean,
              var transferLimit: Int,
              var changeDelay: Int = 0, // change is generic for swap or transfer
              // Dont allow systems where can clash swapping and transferring.
              var transferOpen: Boolean = false,
              var swapOpen: Boolean = false,
              var currentDay: Int = 0,
              var url: String = "",
              var status: Int = 0,
              var start: Option[Timestamp],
              var changeTime: Option[Timestamp],
              var dayEnd: Option[Timestamp],
            ) extends KeyedEntity[Int] {
  val id: Int = 0
  lazy val users = AppDB.leagueUserTable.left(this)
}

class LeagueUser(
                  val leagueId: Int,
                  val userId: Int,
                  var money: Double,
                  var reserveMoney: Double,
                  var lateStart: Boolean,
                  var lateStartTStamp: Option[Timestamp],
                  var changeTStamp: Option[Timestamp]
                ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class LeagueUserStats(
                       val leagueUserId: Long,
                       val day: Int,
                       var points: Double = 0.0,
                       var picks: Int = 0,
                       var bans: Int = 0,
                       var wins: Int = 0,
                       var pointsRank: Int = 0,
                       var picksRank: Int = 0,
                       var bansRank: Int = 0,
                       var winsRank: Int = 0,
                       var oldPointsRank: Int = 0,
                       var oldPicksRank: Int = 0,
                       var oldBansRank: Int = 0,
                       var oldWinsRank: Int = 0
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}

//
class Friend(
              val userId: Int,
              val friendId: Int
            ) extends KeyedEntity[Long] {
  val id: Long = 0
}

//def __init__(self, user, friend):
//self.user = user
//self.friend = friend
//
//
//class Hero(Base):
//__tablename__ = "hero"
//id = Column(Integer, primary_key=True)  # api hero ids start at 1
//name = Column(String(100), nullable=False, index=True)  #index=true?
//team = Column(String(100), index=True)  # for pubg
//league = Column(Integer, ForeignKey(League.id), primary_key=True, nullable=False)
//value = Column(Float, default=10.0)
//points = Column(Integer, default=0)
//picks = Column(Integer, default=0)
//bans = Column(Integer, default=0)
//wins = Column(Integer, default=0)
//active = Column(Boolean, default=True)  # this is for when valve release patch midway through tournament and add/remove from cm
//UniqueConstraint('league', 'hero_id')
//
//# maybe I want day here as well? somewhere to track day value fluctuations
//
//def __init__(self, id, name, value, league, team=None):
//self.id = id
//self.name = name
//self.value = value
//self.league = league
//if team:
//self.team = team
//
//@property
//def username(self):
//# kind of hack to not have to refactor leaderboard pages
//return self.name
//
//
//class TeamHero(Base):
//__tablename__ = "team_hero"
//id = Column(Integer, Sequence('id'), primary_key=True)
//user_id = Column(Integer, ForeignKey(User.id), index=True, nullable=False)
//hero_id = Column(Integer, index=True, nullable=False)
//# commented out due to mapper exception when joining Hero and TeamHero when multiple foreign keys
//# To make it work you give join a tuple I now believe. table, then table column to join I think
//hero_name = Column(String(100))#, ForeignKey(Hero.name))
//league = Column(Integer, index=True, nullable=False)
//cost = Column(Float)
//reserve = Column(Boolean, index=True)
//active = Column(Boolean, index=True)
//UniqueConstraint('league', 'hero_id', 'user_id')
//__table_args__ = (ForeignKeyConstraint([hero_id, league],
//[Hero.id, Hero.league]),
//{})
//
//def __init__(self, user_id, hero_id, league, cost, reserve, active, hero_name=None):
//self.user_id = user_id
//self.hero_id = hero_id
//self.hero_name = hero_name or (item for item in heroes if item["id"] == hero_id).next()["name"]
//self.league = league
//self.cost = cost
//self.reserve = reserve
//self.active = active
//
//
//class Sale(Base):
//__tablename__ = "sale"
//sale_id = Column(Integer, Sequence('sale_id'), primary_key=True)
//user = Column(Integer, ForeignKey('league_user.id'), nullable=False, index=True)  # index true?
//hero = Column(Integer, nullable=False, index=True)
//league_id = Column(Integer, ForeignKey('league.id'), nullable=False, index=True)
//date = Column(DateTime, nullable=False, default=func.now())
//value = Column(Integer, nullable=False)
//cost = Column(Integer, nullable=False)
//is_buy = Column(Boolean, nullable=False)
//is_swap = Column(Boolean, nullable=False)
//
//def __init__(self, user, hero, league_id, value, cost, is_buy, is_swap):
//self.user = user
//self.hero = hero
//self.league_id = league_id
//self.value = value
//self.cost = cost
//self.is_buy = is_buy
//self.is_swap = is_swap
//
//
//class Result(Base):
//__tablename__ = "result"
//id = Column(Integer, Sequence('id'), primary_key=True)
//match_id = Column(BigInteger, nullable=False, index=True)
//tournament_id = Column(Integer, nullable=False)
//hero = Column(Integer, nullable=False)
//result_str = Column(String(10), nullable=False)
//timestamp = Column(Integer)
//applied = Column(Integer, default=0)  # 1 is applied to heroes. 2 for leagues. 3 for battlecups
//series_id = Column(BigInteger)
//is_radiant = Column(Boolean)
//start_tstamp = Column(BigInteger)
//
//def __init__(self, tournament, hero, match, result_str, timestamp, series_id, is_radiant, start_tstamp, applied=0):
//self.tournament_id = tournament
//self.hero = hero
//self.match_id = match
//self.result_str = result_str
//self.timestamp = timestamp
//self.series_id = series_id
//self.is_radiant = is_radiant
//self.start_tstamp = start_tstamp
//self.applied = applied
//
//@staticmethod
//def result_to_value(result_str):
//points_dict = {
//"b1": 1,
//"b2": 2,
//"b3": 4,
//"p1l": -5,
//"p2l": -4,
//"p3l": -2,
//"p1w": 9,
//"p2w": 11,
//"p3w": 15,
//}
//return points_dict[result_str]
//
//@staticmethod
//def result_to_value_pubg(result_str):
//position, kills = result_str.split(",")
//position, kills = int(position), int(kills)
//score = kills * 2
//if position <= 1:
//score += 5
//elif position <= 3:
//score += 3
//elif position <= 5:
//score += 2
//elif position <= 10:
//score += 1
//return score
//
//
//class Match(Base):
//__tablename__ = "match"
//match_id = Column(BigInteger, nullable=False, primary_key=True)
//league = Column(Integer, ForeignKey(League.id), nullable=False)
//tournament = Column(Integer, nullable=False)
//radiant_team = Column(String(100), nullable=False)
//dire_team = Column(String(100), nullable=False)
//radiant_win = Column(Boolean, nullable=False)
//day = Column(Integer)
//
//def __init__(self, match_id, radiant_team, dire_team, radiant_win, day, league_id, tournament):
//self.match_id = match_id
//self.dire_team = dire_team
//self.radiant_team = radiant_team
//self.radiant_win = radiant_win
//self.day = day
//self.league = league_id
//self.tournament = tournament
//
//
//class TeamHeroHistoric(Base):
//__tablename__ = "team_hero_historic"
//id = Column(Integer, Sequence('id'), primary_key=True)
//user_id = Column(Integer, ForeignKey(User.id), index=True, nullable=False)
//hero_id = Column(Integer, index=True, nullable=False)
//# commented out due to mapper exception when joining Hero and TeamHero when multiple foreign keys
//# To make it work you give join a tuple I now believe. table, then table column to join I think
//hero_name = Column(String(100))
//league = Column(Integer, index=True, nullable=False)
//cost = Column(Float)
//day = Column(Integer)
//UniqueConstraint('league', 'hero_id', 'user_id', 'day')
//__table_args__ = (ForeignKeyConstraint([hero_id, league],
//[Hero.id, Hero.league]),
//{})
//
//def __init__(self, user_id, hero_id, league, cost, day, hero_name=None, **kwargs):
//self.user_id = user_id
//self.hero_id = hero_id
//self.hero_name = hero_name or (item for item in heroes if item["id"] == hero_id).next()["name"]
//self.league = league
//self.cost = cost
//self.day = day
//
//
//# # check if I should use polymorphic mapping for this with userLeague
//class HeroDay(Base):
//__tablename__ = "hero_day"
//id = Column(Integer, Sequence('id'), primary_key=True)
//hero_id = Column(Integer, index=True, nullable=False)
//hero_name = Column(String(100), nullable=False)
//league = Column(Integer, index=True)
//day = Column(Integer, index=True)
//stage = Column(Integer)  # 0 qualifiers, 1 group stage, 2 main event
//value = Column(Float)
//points = Column(Float, default=0.0)
//picks = Column(Integer, default=0)
//bans = Column(Integer, default=0)
//wins = Column(Integer, default=0)
//points_rank = Column(Integer)
//wins_rank = Column(Integer)
//picks_rank = Column(Integer)
//bans_rank = Column(Integer)
//__table_args__ = (ForeignKeyConstraint([hero_id, league],
//[Hero.id, Hero.league]),
//{})
//
//def __init__(self, hero_id, hero_name, league, day, stage, value):
//self.hero_id = hero_id
//self.hero_name = hero_name
//self.league = league
//self.day = day
//self.stage = stage
//self.value = value
//
//@property
//def username(self):
//# kind of hack to not have to refactor leaderboard pages
//return self.hero_name
//
//
//class HallOfFame(Base):
//__tablename__ = "hall_of_fame"
//id = Column(Integer, Sequence('id'), primary_key=True)
//game = Column(Integer, ForeignKey(Game.id), index=True, nullable=False)
//league = Column(Integer, nullable=False)  # no FKey because have some entries from deleted leagues
//winner = Column(String(20), default="-")
//runner_up = Column(String(20), default="-")
//
//def __init__(self, game, league):
//self.game = game
//self.league = league
//
//
//class UserXp(Base):
//__tablename__ = "user_xp"
//id = Column(Integer, Sequence('id'), primary_key=True)
//user_id = Column(Integer, ForeignKey(User.id), index=True, nullable=False)
//xp = Column(BigInteger, default=0)
//highest_daily_pos = Column(Integer)
//highest_weekly_pos = Column(Integer)
//all_time_points = Column(BigInteger, default=0)
//
//def __init__(self, user_id):
//self.user_id = user_id
//
//@property
//def level(self):
//return int(1 + 0.097 * (self.xp ** 0.5))
//
//@staticmethod
//def position_xp(position, num_players):
//return 350 * (1 - position / num_players)
//
//
//class Achievement(Base):
//__tablename__ = "achievement"
//id = Column(Integer, Sequence('id'), primary_key=True)
//game = Column(Integer, ForeignKey(Game.id), index=True, nullable=False)
//name = Column(String(40), nullable=False, index=True)
//description = Column(String(300), nullable=False)
//xp = Column(Integer)
//
//def __init__(self, game, name, description, xp):
//self.game = game
//self.name = name
//self.description = description
//self.xp = xp
//
//@property
//def message(self):
//return "Achievement unlocked: %s [%s]" % (self.name, self.description)
//
//
//class UserAchievement(Base):
//__tablename__ = "user_achievement"
//id = Column(Integer, Sequence('id'), primary_key=True)
//achievement = Column(Integer, ForeignKey(Achievement.id), index=True, nullable=False)
//user_id = Column(Integer, ForeignKey(User.id), index=True, nullable=False)
//
//def __init__(self, achievement, user_id):
//self.achievement = achievement
//self.user_id = user_id
//
//
//class Notification(Base):
//__tablename__ = "notification"
//id = Column(Integer, Sequence('id'), primary_key=True)
//user = Column(Integer, ForeignKey(User.id), index=True)
//achievement = Column(Integer, ForeignKey(Achievement.id), nullable=True)
//seen = Column(Boolean, default=False, index=True)
//message = Column(String(100), nullable=False)
//link = Column(String(100), default='')
//
//def __init__(self, user, achievement, message, link=''):
//self.achievement = achievement
//self.user = user
//self.message = message
//self.link = link
//
//
//class ProCircuitTournament(Base):
//__tablename__ = "pro_circuit_tournament"
//id = Column(Integer, Sequence('id'), primary_key=True)
//game = Column(Integer, ForeignKey(Game.id), index=True, nullable=False)
//name = Column(String(100), nullable=False)
//major = Column(Boolean)
//
//def __init__(self, id_, game, name, major):
//self.id = id_
//self.game = game
//self.name = name
//self.major = major
class PasswordReset(
                     // TODO are guid/time stuff vars or actually vals?
                     val userId: Long,
                     var guid: String,
                     //var time: Timestamp,
                     var ip: String, // This is so can ip block anyone who spam resets passwords for someone
                     var counter: Int = 1, // Don't let people get spammed
                   ) extends KeyedEntity[Long] {
  val id: Long = 0
}


object AppDB extends Schema {
  val userTable = table[User]
  val leagueTable = table[League]
  val gameTable = table[Game]
  val passwordResetTable = table[PasswordReset]
  val leagueUserTable =
    manyToManyRelation(leagueTable, userTable).
      via[LeagueUser]((l, u, lu) => (lu.leagueId === l.id, u.id === lu.userId))
  val leagueUserStatsTable = table[LeagueUserStats]

  val leagueUserToleagueUserStats =
    oneToManyRelation(leagueUserTable, leagueUserStatsTable).
      via((lu, lus) => (lu.id === lus.leagueUserId))
}