package utils

import java.sql.Timestamp
import java.text.SimpleDateFormat

import scala.util.Try
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest

object IdParser {
  def parseLongId(id: String, idName: String): Either[Result, Long] = {
    Try(id.toLong).toOption.toRight(BadRequest(f"Invalid $idName ID: $id"))
  }
  def parseIntId(id: String, idName: String): Either[Result, Int] = {
    Try(id.toInt).toOption.toRight(BadRequest(f"Invalid $idName ID: $id"))
  }
}

object TryHelper {

  def tryOrResponse[T](block: () => T, errorResponse: Result): Either[Result, T] = {
    try{
      Right(block())
    }
    catch {
      case e: Exception => print(e); Left(errorResponse)
    }
  }
}


//https://stackoverflow.com/a/9608800
object GroupByOrderedImplicit {
  import collection.mutable.{LinkedHashMap, LinkedHashSet, Map => MutableMap}
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

object Formatter {
  import play.api.libs.json._
  def timestampFormatFactory(formatStr: String): Format[Timestamp] = new Format[Timestamp] {
    val format = new SimpleDateFormat(formatStr)

    // how just super reads?
    def reads(json: JsValue): JsResult[Timestamp] = JsSuccess(new Timestamp(format.parse(json.as[String]).getTime))

    def writes(ts: Timestamp) = JsString(format.format(ts))
  }
}