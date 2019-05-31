package v1.result

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import models._
import anorm._
import anorm.{Macro, RowParser}
import Macro.ColumnNaming
import play.api.db._
import utils.TryHelper.{tryOrResponse, tryOrResponseRollback}
import auth._
import play.api.Logger
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo
import v1.user.UserRepo

case class ResultFormInput(
                            matchId: Long, tournamentId: Option[Long], teamOne: Option[String], teamTwo: Option[String],
                            teamOneScore: Int, teamTwoScore: Int,
                            startTstamp: Option[LocalDateTime], targetAtTstamp: Option[LocalDateTime], pickees: List[PickeeFormInput]
                          )

case class PickeeFormInput(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

case class InternalPickee(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

case class StatsFormInput(field: String, value: Double)

case class FixtureFormInput(matchId: Long, tournamentId: Long, teamOne: String, teamTwo: String, startTstamp: LocalDateTime)

case class FindByTeamsFormInput(teamOne: String, teamTwo: String)

case class PredictionFormInput(matchId: Long, teamOneScore: Int, teamTwoScore: Int)
case class PredictionsFormInput(predictions: List[PredictionFormInput])

case class MatchIdMaybeResultId(matchId: Long, resultId: Option[Long])

class ResultController @Inject()(cc: ControllerComponents, userRepo: UserRepo, resultRepo: ResultRepo, pickeeRepo: PickeeRepo, Auther: Auther)
                                (implicit ec: ExecutionContext, leagueRepo: LeagueRepo, db: Database) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers
  private val logger = Logger("application")
  private val form: Form[ResultFormInput] = {

    Form(
      mapping(
        "matchId" -> of(longFormat),
        "tournamentId" -> optional(of(longFormat)),
        "teamOne" -> optional(nonEmptyText),
        "teamTwo" -> optional(nonEmptyText),
        "teamOneScore" -> number,
        "teamTwoScore" -> number,
        "startTstamp" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm:ss"))),
        "targetAtTstamp" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm:ss"))),
        "pickees" -> list(mapping(
          "id" -> of(longFormat),
          "isTeamOne" -> boolean,
          "stats" -> list(mapping(
            "field" -> nonEmptyText,
            "value" -> of(doubleFormat)
          )(StatsFormInput.apply)(StatsFormInput.unapply))
        )(PickeeFormInput.apply)(PickeeFormInput.unapply))
      )(ResultFormInput.apply)(ResultFormInput.unapply)
    )
  }
  private val fixtureForm: Form[FixtureFormInput] = {
    Form(mapping(
      "matchId" -> of(longFormat),
      "tournamentId" -> of(longFormat),
      "teamOne" -> nonEmptyText,
      "teamTwo" -> nonEmptyText,
      "startTstamp" -> of(localDateTimeFormat("yyyy-MM-dd HH:mm:ss"))
    )(FixtureFormInput.apply)(FixtureFormInput.unapply))
  }

  private val predictionForm: Form[PredictionFormInput] = {
    Form(mapping(
      "matchId" -> of(longFormat),
      "teamOneScore" -> of(intFormat),
      "teamTwoScore" -> of(intFormat)
    )(PredictionFormInput.apply)(PredictionFormInput.unapply))
  }
  private val predictionsForm: Form[PredictionsFormInput] = {
    Form(mapping("predictions" -> list(mapping(
      "matchId" -> of(longFormat),
      "teamOneScore" -> of(intFormat),
      "teamTwoScore" -> of(intFormat)
    )(PredictionFormInput.apply)(PredictionFormInput.unapply)))(PredictionsFormInput.apply)(PredictionsFormInput.unapply))
  }

  private val findByTeamsForm: Form[FindByTeamsFormInput] = {
    Form(mapping(
      "teamOne" -> nonEmptyText,
      "teamTwo" -> nonEmptyText
    )(FindByTeamsFormInput.apply)(FindByTeamsFormInput.unapply))
  }

  implicit val parser = parse.default

  def add(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction).async{ implicit request =>
    db.withConnection { implicit c =>processJsonResult(request.league)}
  }

  def addFixture(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction).async{ implicit request =>
    db.withConnection { implicit c => processJsonAddFixture(request.league)}
  }

//  def addResultsToFixture(leagueId: String, externalMatchId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction).async{ implicit request =>
//    db.withConnection { implicit c => processJsonAddResultsToFixture(request.league, externalMatchId)}
//  }

  private def processJsonResult[A](league: LeagueRow)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[ResultFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: ResultFormInput) = {
      Future {
        //var error: Option[Result] = None
        db.withTransaction { implicit c =>
          (for {
            existingMatchId <- isExistingMatch(league.leagueId, input.matchId)
            validateStarted <- if (leagueRepo.isStarted (league)) Right (true) else Left (BadRequest ("Cannot add results before league started"))
            internalPickee = input.pickees.map (p => InternalPickee (pickeeRepo.getInternalId (league.leagueId, p.id).get, p.isTeamOne, p.stats))
            now = LocalDateTime.now
            targetTstamp = getTargetedAtTstamp(input, league, now)
            // TODO is this getorlese now sensible?
            correctPeriod <- getPeriod (input.startTstamp.getOrElse(now), Some(targetTstamp), league, now)
            matchId <- if (existingMatchId.isDefined) Right(existingMatchId.get) else newMatch(input, league, correctPeriod, targetTstamp, now)
            _ = if (existingMatchId.isDefined) updatedMatch(matchId, input)
            _ = awardPredictions(matchId, input.teamOneScore, input.teamTwoScore)
            insertedResults <- newResults(input, league, matchId, internalPickee)
            insertedStats <- newStats (league, insertedResults.toList, internalPickee)
            updatedStats <- updateStats (insertedStats, league, correctPeriod, targetTstamp)
          } yield Created("Successfully added results")).fold(identity, identity)
        }
      }
    }

    form.bindFromRequest().fold(failure, success)
  }

  private def isExistingMatch(leagueId: Long, externalMatchId: Long)(implicit c: Connection): Either[Result, Option[Long]] = {
    val parser: RowParser[MatchIdMaybeResultId] = Macro.namedParser[MatchIdMaybeResultId](ColumnNaming.SnakeCase)
    val existing = SQL"""
         select m.match_id, result_id from matchu m left join resultu using(match_id) where external_match_id = $externalMatchId and league_id = $leagueId LIMIT 1
       """.as(parser.singleOpt)
    existing match{
      case Some(x) if x.resultId.isDefined => Left(BadRequest("Cannot add results for same match twice"))
      case Some(x) => Right(Some(x.matchId))
      case None => Right(None)
    }
  }

  private def updatedMatch(matchId: Long, input: ResultFormInput)(implicit c: Connection) = {
    SQL"""update matchu set team_one_score = ${input.teamOneScore}, team_two_score = ${input.teamTwoScore}
         where match_id = $matchId
       """.executeUpdate()
  }

  private def awardPredictions(matchId: Long, teamOneScore: Int, teamTwoScore: Int)(implicit c: Connection) = {
    val winningUsers = SQL"""select user_id, prediction_id from prediction where match_id = $matchId AND team_one_score = $teamOneScore AND team_two_score = $teamTwoScore
         AND paid_out = false FOR UPDATE
       """.as((SqlParser.long("user_id") ~ SqlParser.long("prediction_id")).*)
    winningUsers.map({case userId ~ predictionId =>
      SQL"""update useru u set money = money + prediction_win_money from league l
           where u.league_id = l.league_id AND u.user_id = $userId""".executeUpdate()
      SQL"""update prediction set paid_out = true where prediction_id = $predictionId""".executeUpdate()
    })
  }

//  private def processJsonAddResultsToFixture[A](league: LeagueRow, externalMatchId: String)(implicit request: Request[A]): Future[Result] = {
//    def failure(badForm: Form[ResultFormInput]) = {
//      Future.successful(BadRequest(badForm.errorsAsJson))
//    }
//
//    def success(input: ResultFormInput) = {
//      Future {
//        //var error: Option[Result] = None
//        db.withTransaction { implicit c =>
//          (for {
//            validateStarted <- if (leagueRepo.isStarted(league)) Right(true) else Left(BadRequest("Cannot add results before league started"))
//            internalPickee = input.pickees.map(p => InternalPickee(pickeeRepo.getInternalId(league.leagueId, p.id).get, p.isTeamOne, p.stats))
//            now = LocalDateTime.now
//            targetTstamp = getTargetedAtTstamp(input, league, now)
//            correctPeriod <- getPeriod(input.startTstamp, Some(targetTstamp), league, now)
//            insertedMatch <- newMatch(input, league, correctPeriod, targetTstamp, now)
//            insertedResults <- newResults(input, league, insertedMatch, internalPickee)
//            insertedStats <- newStats(league, insertedResults.toList, internalPickee)
//            updatedStats <- updateStats(insertedStats, league, correctPeriod, targetTstamp)
//          } yield Created("Successfully added results")).fold(identity, identity)
//        }
//      }
//    }
//
//    form.bindFromRequest().fold(failure, success)
//  }

  private def processJsonAddFixture[A](league: LeagueRow)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[FixtureFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: FixtureFormInput) = {
      Future {
        val now = LocalDateTime.now
        val targetTstamp = input.startTstamp
        //var error: Option[Result] = None
        db.withTransaction { implicit c =>
          (for {
            correctPeriod <- getPeriod(input.startTstamp, Some(targetTstamp), league, targetTstamp)
            insertedMatch <- newFutureMatch(input, league, correctPeriod, targetTstamp, now)
          } yield Created("Successfully added results")).fold(identity, identity)
        }
      }
    }

    fixtureForm.bindFromRequest().fold(failure, success)
  }

  private def getPeriod(startTstamp: LocalDateTime, targetAtTstamp: Option[LocalDateTime], league: LeagueRow, now: LocalDateTime)(implicit c: Connection): Either[Result, Int] = {
    println(league.applyPointsAtStartTime)
    println(now)

    val targetedAtTstamp = (targetAtTstamp, league.applyPointsAtStartTime) match {
      case (Some(x), _) => x
      case (_, true) => startTstamp
      case _ => now
    }
    logger.info("oOOooooooOOOOoooOOOooo")
    logger.info(targetedAtTstamp.toString)
    tryOrResponse(() => {
      leagueRepo.getPeriodFromTimestamp(league.leagueId, targetedAtTstamp).get.value}, InternalServerError("Cannot add result outside of period"))
  }

  private def getTargetedAtTstamp(input: ResultFormInput, league: LeagueRow, now: LocalDateTime): LocalDateTime = {
    (input.targetAtTstamp, league.applyPointsAtStartTime) match {
      case (Some(x), _) => x
      case (_, true) => input.startTstamp.getOrElse(now)  // TODO check this getOrElse
      case _ => now
    }
  }

  private def newMatch(input: ResultFormInput, league: LeagueRow, period: Int, targetAt: LocalDateTime, now: LocalDateTime)
                      (implicit c: Connection): Either[Result, Long] = {
    tryOrResponse[Long](() => resultRepo.insertMatch(
      league.leagueId, period, input, now, targetAt
    ), InternalServerError("Internal server error adding match"))
  }

  private def newFutureMatch(input: FixtureFormInput, league: LeagueRow, period: Int, targetAt: LocalDateTime, now: LocalDateTime)(implicit c: Connection): Either[Result, Long] = {
    tryOrResponse[Long](() => resultRepo.insertFutureMatch(
      league.leagueId, period, input, now, targetAt
    ), InternalServerError("Internal server error adding fixture"))
  }

  private def newResults(input: ResultFormInput, league: LeagueRow, matchId: Long, pickees: List[InternalPickee])
                        (implicit c: Connection): Either[Result, Iterable[Long]] = {
    tryOrResponseRollback(() => pickees.map(p => resultRepo.insertResult(matchId, p)), c, InternalServerError("Internal server error adding result"))
  }

  private def newStats(league: LeagueRow, resultIds: List[Long], pickees: List[InternalPickee])
                      (implicit c: Connection): Either[Result, List[(StatsRow, Long)]] = {
    tryOrResponseRollback(() => {
      // TODO tidy same code in branches
      if (league.manuallyCalculatePoints) {
        pickees.zip(resultIds).flatMap({ case (ip, resultId) => ip.stats.map(s => {
          val stats = if (s.field == "points") s.value * leagueRepo.getCurrentPeriod(league).get.multiplier else s.value
          val statFieldId = leagueRepo.getStatFieldId(league.leagueId, s.field).get
          (StatsRow(resultRepo.insertStats(resultId, statFieldId, stats), statFieldId, s.value), ip.id)
        })})
      }
      else {
        pickees.zip(resultIds).flatMap({ case (ip, resultId) =>
          val pointsStatFieldId = leagueRepo.getStatFieldId(league.leagueId, "points").get
          var pointsTotal = 0.0
          ip.stats.map(s => {
            val statFieldId = leagueRepo.getStatFieldId(league.leagueId, s.field).get
            val limitIds = pickeeRepo.getPickeeLimitIds(ip.id)
            pointsTotal += s.value * leagueRepo.getCurrentPeriod(league).get.multiplier * leagueRepo.getPointsForStat(
              statFieldId, limitIds
            )
            (StatsRow(resultRepo.insertStats(resultId, statFieldId, s.value), statFieldId, s.value), ip.id)
          }) ++ Seq((StatsRow(resultRepo.insertStats(resultId, pointsStatFieldId, pointsTotal), pointsStatFieldId, pointsTotal), ip.id))
        })

      }
    }, c, InternalServerError("Internal server error adding result"))
  }

  private def updateStats(newStats: List[(StatsRow, Long)], league: LeagueRow, period: Int, targetedAtTstamp: LocalDateTime)
                         (implicit c: Connection): Either[Result, Any] = {
    // dont need to worry about race conditions, because we use targetat tstamp, so it always updates against a consistent team
    // i.e. transfer between update calls, cant let it add points for hafl of one team, then half of a another for other update call
    // TODO can we just remove the transfer table if we're allowing future teams, with future timespans to exist?
    tryOrResponseRollback(() =>
      newStats.foreach({ case (s, pickeeId) => {
        logger.info(s"Updating stats: $s, ${s.value} for internal pickee id: $pickeeId")
        println(s"Updating stats: $s, ${s.value} for internal pickee id: $pickeeId. targeting $targetedAtTstamp")
        val pickeeQ =
          s"""update pickee_stat_period psd set value = value + {newStats} from pickee p
         join pickee_stat ps using(pickee_id)
         where (period is NULL or period = {period}) and ps.pickee_stat_id = psd.pickee_stat_id and
          p.pickee_id = {pickeeId} and ps.stat_field_id = {statFieldId} and league_id = {leagueId};
         """
        SQL(pickeeQ).on(
          "newStats" -> s.value, "period" -> period, "pickeeId" -> pickeeId, "statFieldId" -> s.statFieldId,
          "targetedAtTstamp" -> targetedAtTstamp, "leagueId" -> league.leagueId
        ).executeUpdate()
          val q =
            s"""update user_stat_period usp set value = value + ({newStats} * coalesce(cbm.multiplier, 1.0)) from useru u
         join league l using(league_id)
         join card c using(user_id)
         join team t using(card_id)
         left join card_bonus_multiplier cbm on (cbm.card_id = c.card_id AND cbm.stat_field_id = {statFieldId})
         join pickee p using(pickee_id)
         join user_stat us using(user_id)
         where (period is NULL or period = {period}) and us.user_stat_id = usp.user_stat_id and
                t.timespan @> {targetedAtTstamp}::timestamptz and p.pickee_id = {pickeeId} and us.stat_field_id = {statFieldId}
               and l.league_id = {leagueId};
              """
          //(select count(*) from team_pickee where team_pickee.team_id = t.team_id) = l.team_size;
          val sql = SQL(q).on(
            "newStats" -> s.value, "period" -> period, "pickeeId" -> pickeeId, "statFieldId" -> s.statFieldId,
            "targetedAtTstamp" -> targetedAtTstamp, "leagueId" -> league.leagueId
          )
          //println(sql.sql)
           sql.executeUpdate()
        //println(changed)
    }}), c, InternalServerError("Internal server error updating stats"))
  }

  def getReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future{
      db.withConnection { implicit c =>
        (for {
          period <- tryOrResponse[Option[Int]](() => request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          results = resultRepo.get(request.league.leagueId, period).toList
          success = Ok(Json.toJson(results))
        } yield success).fold(identity, identity)
      }
    }
  }

  def getMatchesReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future{
      db.withConnection { implicit c =>
        (for {
          period <- tryOrResponse[Option[Int]](() => request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          results = resultRepo.getMatches(request.league.leagueId, period).toList
          success = Ok(Json.toJson(results))
        } yield success).fold(identity, identity)
      }
    }
  }

  def getPredictionsReq(leagueId: String, userId: String) = (new LeagueAction(leagueId) andThen new UserAction(userRepo, db)(userId).apply()).async{
    implicit request =>
      Future{
        (for {
          // TODO getOrElse next period?
          period <- tryOrResponse[Option[Int]](() => request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          results = db.withConnection { implicit c => resultRepo.getUserPredictions(request.user.userId, period.getOrElse(1)).toList}
          success = Ok(Json.toJson(results))
        } yield success).fold(identity, identity)
      }
  }

  def upsertPredictionReq(leagueId: String, userId: String) = (new LeagueAction(leagueId) andThen new UserAction(userRepo, db)(userId).apply()).async{
    implicit request =>

      def failure(badForm: Form[PredictionFormInput]) = {Future.successful(BadRequest(badForm.errorsAsJson))}

      def success(input: PredictionFormInput) = {
        Future {
          val results = db.withConnection { implicit c => resultRepo.upsertUserPrediction(
            request.user.userId, request.league.leagueId, input.matchId, input.teamOneScore, input.teamTwoScore
          )}
          results.fold(l => BadRequest(l), r => Ok(Json.toJson(r)))
        }
      }
      predictionForm.bindFromRequest().fold(failure, success)
  }

  def upsertPredictionsReq(leagueId: String, userId: String) = (new LeagueAction(leagueId) andThen new UserAction(userRepo, db)(userId).apply()).async{
    implicit request =>

      def failure(badForm: Form[PredictionsFormInput]) = {Future.successful(BadRequest(badForm.errorsAsJson))}

      def success(input: PredictionsFormInput) = {
        Future {
          val results = db.withConnection { implicit c => resultRepo.upsertUserPredictions(
            request.user.userId, request.league.leagueId, input.predictions
          )}
          results.fold(l => BadRequest(l), r => Ok(Json.toJson(r)))
        }
      }
      predictionsForm.bindFromRequest().fold(failure, success)
  }

  def findByTeams(leagueId: String) = (new LeagueAction(leagueId)).async{
    implicit request =>
      def failure(badForm: Form[FindByTeamsFormInput]) = {Future.successful(BadRequest(badForm.errorsAsJson))}

      def success(input: FindByTeamsFormInput) = {
        Future {
          val match_ = db.withConnection { implicit c => resultRepo.findMatchByTeams(
            request.league.leagueId, input.teamOne, input.teamTwo
          )}
          Ok(Json.toJson(match_))
        }
      }
      findByTeamsForm.bindFromRequest().fold(failure, success)
  }
}
