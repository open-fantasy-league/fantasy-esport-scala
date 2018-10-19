package utils

import scala.util.Try
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest
import entry.SquerylEntrypointForMyApp._

object IdParser {
  def parseIntId(id: String, idName: String): Either[Result, Int] = {
    Try(id.toInt).toOption.toRight(BadRequest(f"Invalid $idName ID: $id"))
  }
}

object CostConverter {
  // use Ints to store cost to make calculations easier (avoid floating point).
  // but divide by 10 for decimal lower cost when display
  def convertCost(cost: Int): Double = cost / 10.0
  def unconvertCost(cost: Double): Int = (cost * 10).toInt
}

object TryInserter {

  def tryInsert[T](block: () => T, errorResponse: Result): Either[Result, T] = {
    Try(block()).toOption.toRight(errorResponse)
  }
}

//object TryInsert {
//
//}