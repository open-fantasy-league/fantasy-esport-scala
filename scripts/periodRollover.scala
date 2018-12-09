import org.squeryl.PrimitiveTypeMode._
import org.squeryl.{SessionFactory, Session}
import org.squeryl.adapters.PostgreSqlAdapter
import app.models._
object Hello {
    def main(args: Array[String]) {
      SessionFactory.concreteFactory = Some(()=>
        Session.create(
          java.sql.DriverManager.getConnection("..."),
          new PostgreSqlAdapter))
        inTransaction{
          AppDB.gameTable.insert(new Game())
        }
        println("Hello, world")
    }
}

