package utils

import scala.util.Try
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest
import entry.SquerylEntrypointForMyApp._

object IdParser {
  def parseLongId(id: String, idName: String): Either[Result, Long] = {
    Try(id.toLong).toOption.toRight(BadRequest(f"Invalid $idName ID: $id"))
  }
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

object TryHelper {

  def tryOrResponse[T](block: () => T, errorResponse: Result): Either[Result, T] = {
    try{
      Right(block())
    }
    catch {
      case e: Exception => {print(e); Left(errorResponse)}
    }
  }
}

import collection.mutable.{LinkedHashMap, LinkedHashSet, Map => MutableMap}

//https://stackoverflow.com/a/9608800
object GroupByOrderedImplicit {
  implicit class GroupByOrderedImplicitImpl[A](val t: Traversable[A]) extends AnyVal {
    def groupByOrdered[K](f: A => K): MutableMap[K, LinkedHashSet[A]] = {
      val map = LinkedHashMap[K,LinkedHashSet[A]]().withDefault(_ => LinkedHashSet[A]())
      for (i <- t) {
        val key = f(i)
        map(key) = map(key) + i
      }
      map
    }
  }
}
