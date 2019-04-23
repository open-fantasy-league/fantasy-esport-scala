package models

import anorm.{ Macro, RowParser }, Macro.ColumnNaming

case class StatsRow(statId: Long, statFieldId: Long, value: Double)

object StatsRow{
  val parser: RowParser[StatsRow] = Macro.namedParser[StatsRow](ColumnNaming.SnakeCase)
}
