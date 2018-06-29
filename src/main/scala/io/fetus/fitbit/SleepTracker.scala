package io.fetus.fitbit

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import io.fetus.fitbit.SleepTracker.{RawSleepData, RawSleepPair, SleepPair}
import org.joda.time.{DateTime, LocalDateTime}
import org.joda.time.format.DateTimeFormat
import play.api.libs.json._
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.DefaultBodyWritables._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

class SleepTracker(auth: FitBitAuth, ws: StandaloneAhcWSClient)(implicit system: ActorSystem, materializer: ActorMaterializer, ec: ExecutionContext) {
  println(s"${LocalDateTime.now} - Initializing Sleep Tracker")

  def getRealTime: (Cancellable, Future[Done]) = {
    println(s"${LocalDateTime.now} - Starting Real Time Sleep Tracking")
    Source
      .tick(Duration.Zero, 5.minutes, () => LocalDateTime.now)
      .map(_())
      .sliding(2, 1)
      .map(x => (x.headOption, x.last))
      .flatMapConcat {
        case (Some(now), after) if now.dayOfYear().get() != after.dayOfYear().get() =>
          Source(List(now, after))
        case (_, after) => Source(List(after))
      }
      .mapAsync(1)(generateRequest)
      .map(_.toList)
      .flatMapConcat(Source.apply)
      .via(flow)
      .toMat(sink)(Keep.both)
      .run()
  }

  def getHistoric: Future[Done] = {
    println(s"${LocalDateTime.now} - Starting Historical Sleep Tracking")
    Source(Stream.from(1))
      .map(LocalDateTime.now().minusDays)
      .map(x => {println(s"${LocalDateTime.now} - Getting Data From ${x.toLocalDate.toString}");x})
      .mapAsync(1)(generateRequest)
      .takeWhile(_.nonEmpty)
      .map(_.toList)
      .flatMapConcat(Source.apply)
      .via(flow)
      .toMat(sink)(Keep.right)
      .run()
  }

  private val flow: Flow[RawSleepData, Seq[SleepPair], NotUsed] = Flow[RawSleepData].map(processData)
  private val sink = Sink.foreach[Seq[SleepPair]] { seq =>
    val binary = seq.flatMap { sleepPair =>
      Seq(
        Json.obj("create" -> Json.obj("_index" -> "sleep", "_type" -> "pair", "_id" -> sleepPair.measured_at.toString)),
        Json.toJsObject(sleepPair)
      )
    }.mkString("\n") + "\n"

    ws
      .url("http://localhost:9200/_bulk")
      .withHttpHeaders(
        "Content-Type" -> "application/x-ndjson"
      )
      .post(binary)
      .map(_.body)
      .map(Json.parse)
      .map(_ \ "items" \\ "status")
      .map(_.map(_.as[Int]).count(_ == 201))
      .foreach(count => println(s"\tInserted $count records"))
  }
  private def generateRequest: LocalDateTime => Future[Seq[RawSleepData]] = (date: LocalDateTime) => {
    val dateString: String = date.toString(DateTimeFormat.forPattern("yyyy-MM-dd"))
    for {
      access_token <- auth.get()
      res <- ws
        .url(s"https://api.fitbit.com/1.2/user/-/sleep/date/$dateString.json")
        .withHttpHeaders(
          "Authorization" -> s"Bearer $access_token"
        )
        .get()
        .map(_.body)
        .map(Json.parse)
        .map(_ \ "sleep")
        .map(_.as[Seq[RawSleepData]])
    } yield res
  }
  private def processData(raw: RawSleepData): Seq[SleepPair] = {
    raw.data
      .map {
        case RawSleepPair(dateTimeString, levelString) =>
          val dateTime = Try(DateTime.parse(dateTimeString, DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")))
          println(levelString)
          val level: Byte = levelString match {
            case "restless" => 5
            case "wake" => 4
            case "awake" => 4
            case "rem" => 3
            case "light" => 2
            case "asleep" => 1
            case "deep" => 0

          }
          SleepPair(dateTime.get, level)
      }
  }
}

object SleepTracker {
  case class RawSleepPair(dateTime: String, level: String)
  case class RawSleepData(date: String, data: Seq[RawSleepPair])
  case class SleepPair(measured_at: DateTime, level: Byte)

  implicit val dateTimeWrites: Writes[DateTime] = (o: DateTime) => JsString(o.toString)

  implicit val sleepPairWrites: OWrites[SleepPair] = Json.writes[SleepPair]

  implicit val rawSleepPairReads: Reads[RawSleepPair] = Json.reads[RawSleepPair]
  implicit val rawSleepDataReads: Reads[RawSleepData] = (json: JsValue) => for {
    date <- (json \ "dateOfSleep").validate[String]
    pairs <- (json \ "levels" \ "data").validate[Seq[RawSleepPair]]
  } yield RawSleepData(date, pairs)
}
