package io.fetus.fitbit

import akka.{Done, NotUsed}
import akka.actor.{ActorContext, ActorSystem, Cancellable}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}
import io.fetus.fitbit.HeartRateTracker._
import org.joda.time.{DateTime, LocalDate, LocalDateTime, LocalTime}
import org.joda.time.format.DateTimeFormat
import play.api.libs.json._
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.DefaultBodyWritables._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class HeartRateTracker(auth: FitBitAuth, ws: StandaloneAhcWSClient)(implicit system: ActorSystem, materializer: ActorMaterializer, ec: ExecutionContext) {
  println(s"${LocalDateTime.now} - Initializing Heart Rate Tracker")

  def getRealTime: (Cancellable, Future[Done]) = {
    println(s"${LocalDateTime.now} - Starting Real Time Heart Rate Tracking")
    Source
      .tick(Duration.Zero, 5.minute, () => LocalDateTime.now)
      .map(_())
      .sliding(2, 1)
      .map(x => (x.headOption, x.last))
      .flatMapConcat {
        case (Some(now), after) if now.dayOfYear().get() != after.dayOfYear().get() =>
          Source(List(now, after))
        case (_, after) => Source(List(after))
      }
      .mapAsync(1)(generateRequest)
      .via(flow)
      .toMat(sink)(Keep.both)
      .run()
  }

  def getHistoric: Future[Done] = {
    println(s"${LocalDateTime.now} - Starting Historical Heart Rate Tracking")
    Source(Stream.from(0))
      .map(LocalDateTime.now().minusDays)
      .takeWhile(x => !x.toLocalDate.equals(LocalDate.parse("2018-04-08")))
      .map(x => {println(s"${LocalDateTime.now} - Getting Data From ${x.toLocalDate.toString}");x})
      .mapAsync(1)(generateRequest)
      .via(flow)
      .toMat(sink)(Keep.right)
      .run()
  }

  private val flow: Flow[RawHRData, Seq[HRPair], NotUsed] = Flow[RawHRData].map(processData)
  private val sink: Sink[Seq[HRPair], Future[Done]] = Sink.foreach[Seq[HRPair]] { seq =>
    val binary = seq.flatMap { hrPair =>
      Seq(
        Json.obj("create" -> Json.obj("_index" -> "heartrate", "_type" -> "pair", "_id" -> hrPair.measured_at.toString)),
        Json.toJsObject(hrPair)
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
  private def generateRequest: LocalDateTime => Future[RawHRData] = (date: LocalDateTime) => {
    val dateString: String = date.toString(DateTimeFormat.forPattern("yyyy-MM-dd"))
    for {
      access_token <- auth.get()
      res <- ws
        .url(s"https://api.fitbit.com/1/user/-/activities/heart/date/$dateString/1d/1sec.json")
        .withHttpHeaders(
          "Authorization" -> s"Bearer $access_token"
        )
        .get()
        .map(_.body)
        .map(Json.parse)
        .map(_.as[RawHRData])
    } yield res
  }
  private def processData(raw: RawHRData): Seq[HRPair] = {
    val date: DateTime = DateTime.parse(raw.date, DateTimeFormat.forPattern("yyyy-MM-dd"))
    raw.pairs
      .map {
        case RawHRPair(timeString, value) =>
          val time = LocalTime.parse(timeString, DateTimeFormat.forPattern("HH:mm:ss"))
          HRPair(date.withTime(time), value)
      }
  }
}

object HeartRateTracker {

  case class RawHRPair(time: String, value: Int)

  case class RawHRData(date: String, pairs: Seq[RawHRPair])

  case class HRPair(measured_at: DateTime, heart_rate: Int)

  implicit val dateTimeWrites: Writes[DateTime] = (o: DateTime) => JsString(o.toString)

  implicit val hrPairWrites: OWrites[HRPair] = Json.writes[HRPair]

  implicit val rawHRPairReads: Reads[RawHRPair] = Json.reads[RawHRPair]
  implicit val rawHRDataReads: Reads[RawHRData] = (json: JsValue) => for {
    date <- (json \ "activities-heart" \\ "dateTime").head.validate[String]
    pairs <- (json \ "activities-heart-intraday" \ "dataset").validate[Seq[RawHRPair]]
  } yield RawHRData(date, pairs)
}
