package io.fetus.fitbit

import java.io.PrintWriter
import java.time.LocalDateTime

import akka.actor.{ActorContext, ActorSystem}
import akka.stream.ActorMaterializer
import com.typesafe.config.{Config, ConfigFactory}
import play.api.libs.json.{Json, Reads}
import play.api.libs.ws.WSAuthScheme
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.DefaultBodyWritables._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.io.Source

class FitBitAuth(ws: StandaloneAhcWSClient)(implicit system: ActorSystem, materializer: ActorMaterializer, ec: ExecutionContext) {
  case class Auth(access_token: String, refresh_token: String, expires_in: Long)
  implicit val authReads: Reads[Auth] = Json.reads[Auth]
  private val config: Config = ConfigFactory.load()
  private val initial_refresh_token = Source.fromFile("refresh_token").mkString.trim
  private val initialized = Promise[Unit]()
  private val refresh_token: Agent[Auth] = Agent(Auth("", initial_refresh_token, 0))

  def get(): Future[String] = for {
    _ <- initialized.future
    auth <- refresh_token()
  } yield auth.access_token

  println(s"${LocalDateTime.now} - Initializing Authenticator")
  println(s"\tInitial Refresh Token - $initial_refresh_token")

  refresh()

  def refresh(): Future[Unit] = {
    for {
      old_auth <- refresh_token()
      new_auth <- ws
        .url("https://api.fitbit.com/oauth2/token")
        .withHttpHeaders(
          "Content-Type" -> "application/x-www-form-urlencoded"
        )
        .withAuth(client_id, client_secret, WSAuthScheme.BASIC)
        .post(Map(
          "grant_type" -> Seq("refresh_token"),
          "refresh_token" -> Seq(old_auth.refresh_token)
        ))
        .map(_.body)
        .map(Json.parse)
        .map(_.as[Auth])
    } yield {
      new PrintWriter("refresh_token") { try { write(new_auth.refresh_token) } finally { close() } }
      system.scheduler.scheduleOnce((new_auth.expires_in / 2).seconds)(refresh())
      refresh_token.send(new_auth)
      println(s"${LocalDateTime.now} - Refreshed")
      println(s"\tNew Access Token - ${new_auth.access_token}")
      println(s"\tNew Refresh Token - ${new_auth.refresh_token}")
      if (!initialized.isCompleted) initialized.success(())
    }
  }


  private val client_id: String = config.getString("fitbit.client_id")
  private val client_secret: String = config.getString("fitbit.client_secret")
}
