import play.api.libs.json._
case class RankingRow(
                       userId: Long, username: String, value: Double, previousRank: Option[Int], pickeeId: Option[Long],
                       pickeeName: Option[String], price: Option[BigDecimal]
                     )

case class Ranking(
                    userId: Long, username: String, value: Double, rank: Int, previousRank: Option[Int],
                    team: Iterable[PickeeOut]
                  )

case class LeagueRankings(leagueId: Long, leagueName: String, statField: String, rankings: Iterable[Ranking])


object Ranking{
  implicit val implicitWrites = new Writes[Ranking] {
    def writes(ranking: Ranking): JsValue = {
      Json.obj(
        "userId" -> ranking.userId,
        "username" -> ranking.username,
        "value" -> ranking.value,
        "rank" -> ranking.rank,
        "previousRank" -> ranking.previousRank,
        "team" -> ranking.team
      )
    }
  }
}

object LeagueRankings{
  implicit val implicitWrites = new Writes[LeagueRankings] {
    def writes(leagueRank: LeagueRankings): JsValue = {
      Json.obj(
        "leagueId" -> leagueRank.leagueId,
        "leagueName" -> leagueRank.leagueName,
        "rankings" -> leagueRank.rankings,
        "statField" -> leagueRank.statField,
      )
    }
  }
}