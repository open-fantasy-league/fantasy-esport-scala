package utils
import scala.util.{Failure, Success, Try}
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest

object IdParser {
  def parseIntId(id: String, callback: (Int) => Result): Result = {
    Try(id.toInt) match {
      case Success(convertedId) => callback(convertedId)
      case Failure(_) => BadRequest(f"Invalid ID: $id")
    }
  }
}


