import sbt.Keys._

scalaVersion in ThisBuild := "2.12.6"

crossScalaVersions := Seq("2.11.12", "2.12.4")

resolvers += Resolver.sbtPluginRepo("releases")

libraryDependencies += guice
libraryDependencies += evolutions
libraryDependencies += "org.joda" % "joda-convert" % "1.9.2"
libraryDependencies += "net.logstash.logback" % "logstash-logback-encoder" % "4.11"

libraryDependencies += "com.netaporter" %% "scala-uri" % "0.4.16"
libraryDependencies += "net.codingwell" %% "scala-guice" % "4.1.1"

libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test
libraryDependencies += "org.mockito" % "mockito-core" % "1.9.5" % Test
libraryDependencies += "com.github.t3hnar" %% "scala-bcrypt" % "3.1"
//libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.2.27"
libraryDependencies += filters

libraryDependencies ++=  Seq(
  jdbc,
  "org.squeryl" %% "squeryl" % "0.9.10",
  "org.playframework.anorm" %% "anorm" % "2.6.4",
//  "mysql" % "mysql-connector-java" % "5.1.10",
//  "com.h2database" % "h2" % "1.4.196",
  //"org.postgresql" % "postgresql" % "9.3-1102-jdbc41"
  "org.postgresql" % "postgresql" % "42.2.2"
)
// The Play project itself
lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := """fantasy-esport-scala""",
  )

// Documentation for this project:
//    sbt "project docs" "~ paradox"
//    open docs/target/paradox/site/index.html
