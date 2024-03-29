val akkaVer = "2.5.11"
val casbahVer = "3.1.1"
val commonsIoVer = "2.4"
val embeddedMongoVer = "2.0.3"
val logbackVer = "1.1.3"
val scalaVer = "2.12.5"
val scalatestVer = "3.0.5"

organization := "com.moveware"
name := "writeJournal"

version := "0.1"

scalaVersion := scalaVer

scalacOptions ++= Seq(
  "-encoding", "UTF-8",
  "-deprecation",
  "-unchecked",
  "-feature",
  "-language:postfixOps",
  "-target:jvm-1.8")

parallelExecution in ThisBuild := false

parallelExecution in Test := false
logBuffered in Test := false

libraryDependencies ++= Seq(
  "ch.qos.logback"       % "logback-classic"             % logbackVer         % "test",
  "commons-io"           % "commons-io"                  % commonsIoVer       % "test",
  "com.typesafe.akka"   %% "akka-testkit"                % akkaVer            % "test",
  "com.typesafe.akka"   %% "akka-persistence"            % akkaVer            % "compile",
  "com.typesafe.akka"   %% "akka-persistence-tck"        % akkaVer            % "test",
  "de.flapdoodle.embed"  % "de.flapdoodle.embed.mongo"   % embeddedMongoVer   % "test",
  "org.mongodb"         %% "casbah"                      % casbahVer          % "compile" pomOnly(),
  "org.scalatest"       %% "scalatest"                   % scalatestVer       % "test"
)

publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)
