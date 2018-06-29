package io.fetus.fitbit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.ExecutionContext

object Main {
  implicit private val system: ActorSystem = ActorSystem()
  implicit private val materializer: ActorMaterializer = ActorMaterializer()
  implicit private val ec: ExecutionContext = system.dispatcher

  private val ws: StandaloneAhcWSClient = StandaloneAhcWSClient()

  def main(args: Array[String]): Unit = {
    val auth: FitBitAuth = new FitBitAuth(ws)
    val hrTracker: HeartRateTracker = new HeartRateTracker(auth, ws)
//    val sleepTracker: SleepTracker = new SleepTracker(auth, ws)
    hrTracker.getHistoric
    hrTracker.getRealTime
//    sleepTracker.getHistoric
//    sleepTracker.getRealTime
  }
}
