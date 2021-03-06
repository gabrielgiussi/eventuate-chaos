package com.rbmhtechnology.eventuate.chaos

import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.pattern.ask
import akka.util.Timeout
import com.rbmhtechnology.eventuate.EventsourcedActor
import com.rbmhtechnology.eventuate.ReplicationEndpoint

import scala.util.Failure
import scala.util.Success
import scala.concurrent.duration._

object ChaosStateCassandra extends ChaosCassandraSetup {
  implicit val system = getSystem
  val endpoint = getEndpoint
  val actor = system.actorOf(supervised(ChaosStateActor.props(endpoint.logs(ReplicationEndpoint.DefaultLogName)), "ChaosState"))
  val interface = system.actorOf(Props(new ChaosStateInterface(actor)))
}

object ChaosStateLeveldb extends ChaosLeveldbSetup {
  implicit val system = getSystem
  val endpoint = getEndpoint
  val actor = system.actorOf(supervised(ChaosStateActor.props(endpoint.logs(ReplicationEndpoint.DefaultLogName)), "ChaosState"))
  val interface = system.actorOf(Props(new ChaosStateInterface(actor)))
}

class ChaosStateInterface(stateActor: ActorRef) extends ChaosInterface {
  import ChaosStateActor._

  implicit val timeout = Timeout(1.seconds)

  def handleCommand = {
    case (x, Some(id), rcv) =>
      val update = Update(x, id)
      stateActor ? update map (_ => reply(update.value, rcv)) onFailure closeOnError(rcv)

    case ("get", None, rcv) =>
      (stateActor ? GetState).mapTo[State] map (x => reply(x.value, rcv)) onFailure closeOnError(rcv)

    case ("crash", None, rcv) =>
      stateActor ! CrashIt
      rcv ! Tcp.Close
  }
}

object ChaosStateActor {
  case class Update(location: String, id: Int) {
    def value = s"$location-$id"
  }

  case object UpdateOk

  case object GetState

  case object CrashIt

  case class State(state: Vector[String]) {
    def value = s"[${state.mkString(",")}]"
  }

  object ExpectedCrashException
    extends RuntimeException("Crashing due to CrashIt command")

  def props(eventLog: ActorRef): Props = Props(new ChaosStateActor(eventLog))
}

class ChaosStateActor(val eventLog: ActorRef) extends EventsourcedActor with ActorLogging {
  import ChaosStateActor._

  val id = "chaos"

  private var state: Vector[String] = Vector.empty

  log.info("Starting ChaosStateActor")

  override def postStop() = {
    super.postStop()
    log.info("Stopping ChaosStateActor - current state: {}", state.toString)
  }

  override def onRecovery = {
    case Success(()) =>
      log.info("Recovery succeeded - current state: {}", state)
    case _ =>
  }

  override def onCommand: Receive = {
    case GetState =>
      sender ! State(state)

    case CrashIt =>
      throw ExpectedCrashException

    case x: Update =>
      val snd = sender()

      persist(x) {
        case Success(_) =>
          snd ! UpdateOk
          log.info("Successfully persisted '{}', current state: {}", x, state)
        case Failure(err) =>
          log.error(err, "Failed to persist: {}", x)
      }
  }

  override def onEvent: Receive = {
    case update: Update =>
      state = state :+ update.value
  }
}
