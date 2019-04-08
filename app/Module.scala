import javax.inject._

import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import play.api.{Configuration, Environment}
import v1.league.{LeagueRepo, LeagueRepoImpl}
import v1.pickee.{PickeeRepo, PickeeRepoImpl}
import v1.result.{ResultRepo, ResultRepoImpl}
import v1.leagueuser.{LeagueUserRepo, LeagueUserRepoImpl}
import v1.team.{TeamRepo, TeamRepoImpl}
import v1.transfer.{TransferRepo, TransferRepoImpl}
import v1.admin.{AdminRepo, AdminRepoImpl}


/**
  * Sets up custom components for Play.
  *
  * https://www.playframework.com/documentation/latest/ScalaDependencyInjection
  */
class Module(environment: Environment, configuration: Configuration)
    extends AbstractModule
    with ScalaModule {

  override def configure() = {
    println("configure called")
    bind[LeagueRepo].to[LeagueRepoImpl].in[Singleton]
    bind[PickeeRepo].to[PickeeRepoImpl].in[Singleton]
    bind[ResultRepo].to[ResultRepoImpl].in[Singleton]
    bind[TeamRepo].to[TeamRepoImpl].in[Singleton]
    bind[TransferRepo].to[TransferRepoImpl].in[Singleton]
    bind[LeagueUserRepo].to[LeagueUserRepoImpl].in[Singleton]
    bind[AdminRepo].to[AdminRepoImpl].in[Singleton]
    bind(classOf[SquerylInitialization]).asEagerSingleton()
  }
}

import play.api.db.Database
import org.squeryl.{SessionFactory, Session}
import org.squeryl.adapters.PostgreSqlAdapter
@Singleton
class SquerylInitialization @Inject()(conf: Configuration, DB: Database) {
  SessionFactory.concreteFactory = Some(()=>
      Session.create(
      DB.getConnection(),
  new PostgreSqlAdapter))
}
