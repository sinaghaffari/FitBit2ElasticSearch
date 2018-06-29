name := "FitBitESSyncer"

version := "0.1"

scalaVersion := "2.12.5"

libraryDependencies ++= {
  val playWSVersion = "1.1.7"
  Seq(
    "com.typesafe.play" %% "play-ahc-ws-standalone" % playWSVersion,
    "com.typesafe.play" %% "play-ws-standalone-json" % playWSVersion
  )
}

libraryDependencies ++= {
  val akkaVersion = "2.5.12"
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion
  )
}