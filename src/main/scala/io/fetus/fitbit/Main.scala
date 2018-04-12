package io.fetus.fitbit

import java.io.{File, PrintWriter}

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.typesafe.config.{Config, ConfigFactory}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalTime}
import play.api.libs.json.{Json, Reads}
import play.api.libs.ws.DefaultBodyWritables._
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.{StandaloneWSResponse, WSAuthScheme}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io

object Main {
  case class Data(time: String, value: Int)
  implicit private val dataReads: Reads[Data] = Json.reads[Data]
  implicit private val system: ActorSystem = ActorSystem()
  implicit private val materializer: ActorMaterializer = ActorMaterializer()
  implicit private val ec: ExecutionContext = system.dispatcher

  private val ws: StandaloneAhcWSClient = StandaloneAhcWSClient()
  private val config: Config = ConfigFactory.load()

  def main(args: Array[String]): Unit = {
    val client_id = config.getString("fitbit.client_id")
    val client_secret = config.getString("fitbit.client_secret")
    println(s"${DateTime.now} - Started")
    println(s"\tClient Id - $client_id")
    println(s"\tClient Secret - $client_secret")
    for {
      _ <- getHistoricalData(client_id, client_secret)
      _ <- getContinuousData(client_id, client_secret)
    } yield ()

  }

  def getContinuousData(client_id: String, client_secret: String): Future[Done] = {
    println(s"${DateTime.now} - Getting Continuous Data")
    Source
      .repeat(refreshAccessKey(client_id, client_secret))
      .mapAsync(1)(x => x)
      .flatMapConcat { access_token =>
        val date = DateTime.now().toString(DateTimeFormat.forPattern("yyyy-MM-dd"))
        val req = ws
          .url(s"https://api.fitbit.com/1/user/-/activities/heart/date/$date/1d/1sec.json")
          .withHttpHeaders(
            "Authorization" -> s"Bearer $access_token"
          )
        Source
          .tick(Duration.Zero, 5.minute, req)
          .map(_.get())
          .mapAsync(1)(x => x)
          .takeWhile {
            case res: StandaloneWSResponse if res.status == 200 => true
            case _ => false
          }
      }
      .map(_.body)
      .map(Json.parse)
      .map(json => ((json \ "activities-heart" \\ "dateTime").head.as[String], (json \ "activities-heart-intraday" \ "dataset").as[Seq[Data]]))
      .map(x => x._2.map(d => (DateTime.parse(x._1, DateTimeFormat.forPattern("yyyy-MM-dd")).withTime(LocalTime.parse(d.time, DateTimeFormat.forPattern("HH:mm:ss"))), d.value)))
      .runForeach { seq =>
        val binary = seq.flatMap {
          case (dt, v) =>
            Seq(
              Json.obj("create" -> Json.obj("_index" -> "heartrate", "_type" -> "pair", "_id" -> dt.toString)),
              Json.obj("measured_at" -> dt.toString, "heart_rate" -> v)
            )
        }.mkString("\n") + "\n"
        ws
          .url("http://localhost:9200/_bulk")
          .withHttpHeaders(
            "Content-Type" -> "application/x-ndjson"
          )
          .post(binary)
      }
  }

  def getHistoricalData(client_id: String, client_secret: String): Future[Done] = {
    println(s"${DateTime.now} - Getting Historical Data")
    Source
      .repeat(refreshAccessKey(client_id, client_secret))
      .mapAsync(1)(x => x)
      .flatMapConcat { access_token =>
        Source(Stream.from(0))
          .map(x => DateTime.now().minusDays(x).toString(DateTimeFormat.forPattern("yyyy-MM-dd")))
          .map { date =>
            println(s"${DateTime.now} - Getting data from $date")
            ws
              .url(s"https://api.fitbit.com/1/user/-/activities/heart/date/$date/1d/1sec.json")
              .withHttpHeaders(
                "Authorization" -> s"Bearer $access_token"
              )
              .get()
          }
          .mapAsync(1)(x => x)
          .takeWhile {
            case res: StandaloneWSResponse if res.status == 200 => true
            case _ => false
          }
      }
      .map(_.body)
      .map(Json.parse)
      .map(json => ((json \ "activities-heart" \\ "dateTime").head.as[String], (json \ "activities-heart-intraday" \ "dataset").as[Seq[Data]]))
      .takeWhile(_._2.nonEmpty)
      .map(x => x._2.map(d => (DateTime.parse(x._1, DateTimeFormat.forPattern("yyyy-MM-dd")).withTime(LocalTime.parse(d.time, DateTimeFormat.forPattern("HH:mm:ss"))), d.value)))
      .runForeach { seq =>
        val binary = seq.flatMap {
          case (dt, v) =>
            Seq(
              Json.obj("create" -> Json.obj("_index" -> "heartrate", "_type" -> "pair", "_id" -> dt.toString)),
              Json.obj("measured_at" -> dt.toString, "heart_rate" -> v)
            )
        }.mkString("\n") + "\n"
        ws
          .url("http://localhost:9200/_bulk")
          .withHttpHeaders(
            "Content-Type" -> "application/x-ndjson"
          )
          .post(binary)
      }
  }

  def refreshAccessKey(client_id: String, client_secret: String): Future[String] = {
    println(s"${DateTime.now()} - Refreshing Access Key")
    val refresh_token = io.Source.fromFile("refresh_token").mkString.trim
    ws
      .url("https://api.fitbit.com/oauth2/token")
      .withAuth(client_id, client_secret, WSAuthScheme.BASIC)
      .post(Map(
        "grant_type" -> Seq("refresh_token"),
        "refresh_token" -> Seq(refresh_token)
      ))
      .map(_.body)
      .map(Json.parse)
      .map { json =>
        println(s"${DateTime.now()} - Refreshed Access Key")
        println(s"\tResponse - \n ${Json.prettyPrint(json)}")
        println(s"\tRefresh Token - ${(json \ "refresh_token").as[String]}")
        println(s"\tAccess Token - ${(json \ "access_token").as[String]}")
        new PrintWriter(new File("refresh_token")) { try { write((json \ "refresh_token").as[String])} finally {close()}}
        (json \ "access_token").as[String]
      }
  }
}
