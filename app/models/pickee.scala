case class PickeeOut(pickeeId: Long, name: String, cost: BigDecimal)

object PickeeOut {
  implicit val implicitWrites = new Writes[PickeeOut] {
    def writes(x: PickeeOut): JsValue = {
      Json.obj(
        "id" -> x.pickeeId,
        "name" -> x.name,
        "cost" -> x.cost
      )
    }
  }
}