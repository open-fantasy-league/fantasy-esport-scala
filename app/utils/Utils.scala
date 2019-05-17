package utils

import java.time.LocalDateTime
import java.text.SimpleDateFormat

import scala.util.Try
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest
import java.io._
import java.sql.Connection
import java.time.format.DateTimeFormatter

object IdParser {
  def parseLongId(id: String, idName: String): Either[Result, Long] = {
    Try(id.toLong).toOption.toRight(BadRequest(f"Invalid $idName ID: $id"))
  }
  def parseIntId(id: String, idName: String): Either[Result, Int] = {
    Try(id.toInt).toOption.toRight(BadRequest(f"Invalid $idName ID: $id"))
  }

  def parseTimestamp(tstamp: Option[String]): Either[Result, Option[LocalDateTime]] = {
    val format = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    Try(tstamp.map(t => LocalDateTime.parse(t, format))).toOption.toRight(BadRequest(f"Invalid time format: $tstamp"))
  }
}

object TryHelper {

  def tryOrResponse[T](block: () => T, errorResponse: Result): Either[Result, T] = {
    try{
      Right(block())
    }
    catch {
      case e: Exception => {
        // https://alvinalexander.com/scala/how-convert-stack-trace-exception-string-print-logger-logging-log4j-slf4j
        val sw = new StringWriter
        e.printStackTrace(new PrintWriter(sw))
        println(sw.toString)
        Left(errorResponse)
      }
    }
  }

  def tryOrResponseRollback[T](block: () => T, c: Connection, errorResponse: Result): Either[Result, T] = {
    try{
      Right(block())
    }
    catch {
      case e: Exception => {
        c.rollback()
        // https://alvinalexander.com/scala/how-convert-stack-trace-exception-string-print-logger-logging-log4j-slf4j
        val sw = new StringWriter
        e.printStackTrace(new PrintWriter(sw))
        println(sw.toString)
        Left(errorResponse)
      }
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

//object Formatter {
//  import play.api.libs.json._
//
//  import play.api.data.format.Formats._
//  def timestampFormatFactory(formatStr: String): Format[LocalDateTime] = new Format[LocalDateTime] {
//    val format = new localDateTimeFormat(formatStr)
//
//    def reads(json: JsValue): JsResult[LocalDateTime] = JsSuccess(format.parse(json.as[String]))
//
//    def writes(ts: LocalDateTime) = JsString(format.format(ts))
//  }
//}