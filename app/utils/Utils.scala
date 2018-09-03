package utils

import scala.util.Try
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest

object IdParser {
  def parseIntId(id: String, idName: String): Either[Result, Int] = {
    Try(id.toInt).toOption.toRight(BadRequest(f"Invalid $idName ID: $id"))
  }
}


