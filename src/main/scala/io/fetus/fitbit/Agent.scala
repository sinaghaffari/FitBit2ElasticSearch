package io.fetus.fitbit

import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag

case class Agent[T:ClassTag](initValue: T)(implicit system: ActorSystem) {
  val actor: ActorRef = system.actorOf(Props(new this.AgentActor()))
  implicit val timeout: Timeout = Timeout(1.minute)
  actor ! SetValue(initValue)

  def apply(): Future[T] = {
    (actor ? GetValue).mapTo[T]
  }

  def send(v: T): Unit = {
    actor ! SetValue(v)
  }
  def send(f: T => T): Unit = {
    actor ! ModValue(f)
  }

  case class SetValue(value: T)
  case class ModValue(f: T => T)
  case object GetValue
  class AgentActor extends Actor {
    var v: Option[T] = None
    override def receive: Receive = {
      case SetValue(a) => v = Some(a)
      case ModValue(f) => v = Some(f(v.get))
      case GetValue => sender() ! v.get
    }
  }
}

