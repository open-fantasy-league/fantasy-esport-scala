package utils

import java.time.LocalDateTime

import scala.util.Try
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest
import java.io._
import java.sql.Connection
import java.time.format.DateTimeFormatter

case class NameValueInput(name: String, value: String)

object IdParser {
  def parseLongId(id: String, idName: String): Either[Result, Long] = {
    Try(id.toLong).toOption.toRight(BadRequest(f"Invalid $idName ID: $id"))
  }
  def parseIntId(id: Option[String], idName: String, required: Boolean = false, max: Option[Int] = None): Either[Result, Option[Int]] = {
    if (id.isEmpty && required) Left(BadRequest(f"$idName parameter required"))
    else {
      Try(id.map(_.toInt)).toOption.toRight(BadRequest(f"Invalid $idName ID: $id")) match {
        case Left(x) => Left(x)
        case Right(x) if max.isDefined && x.getOrElse(0) > max.get => Left(BadRequest(f"Maximum value for $idName is ${max.get}"))
        case Right(x) => Right(x)
      }
    }
  }

  def parseTimestamp(tstamp: Option[String]): Either[Result, Option[LocalDateTime]] = {
    val format = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    Try(tstamp.map(t => LocalDateTime.parse(t, format))).toOption.toRight(BadRequest(f"Invalid time format: $tstamp"))
  }
}

object TryHelper {

  def tryOrResponse[T](block: => T, errorResponse: Result): Either[Result, T] = {
    try{
      Right(block)
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

  def tryOrResponseRollback[T](block: => T, c: Connection, errorResponse: Result): Either[Result, T] = {
    try{
      Right(block)
    }
    catch {
      case e: Exception => {
        // TODO use an implicit logger?
        println("Rolling back transaction")
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

object Utils{
  //https://stackoverflow.com/a/11108013
  def trunc(x: Double, n: Int): Double = {
    def p10(n: Int, pow: Long = 10): Long = if (n==0) pow else p10(n-1,pow*10)
    if (n < 0) {
      val m = p10(-n).toDouble
      math.round(x/m) * m
    }
    else {
      val m = p10(n).toDouble
      math.round(x*m) / m
    }
  }

  //http://biercoff.com/easily-measuring-code-execution-time-in-scala/
  def time[R](block: => R): R = {
    val t0 = System.nanoTime()
    val result = block    // call-by-name
    val t1 = System.nanoTime()
    println("Elapsed time: " + (t1 - t0).toFloat / 1000000000 + "s")
    result
  }

  def camelToSnake(text: String) = text.drop(1).foldLeft(text.headOption.map(_.toLower + "") getOrElse "") {
    case (acc, c) if c.isUpper => acc + "_" + c.toLower
    case (acc, c) => acc + c
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