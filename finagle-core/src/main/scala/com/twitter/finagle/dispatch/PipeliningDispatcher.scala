package com.twitter.finagle.dispatch

import com.twitter.concurrent.AsyncQueue
import com.twitter.conversions.time._
import com.twitter.finagle.{Failure, Stack}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util.{Duration, Future, Promise, Time, Timer, Try}

/**
 * A generic pipelining dispatcher, which assumes that servers will
 * respect normal pipelining semantics, and that replies will be sent
 * in the same order as requests were sent.  Exploits
 * [[GenSerialClientDispatcher]] to serialize requests.
 *
 * Because many requests might be sharing the same transport,
 * [[com.twitter.util.Future Futures]] returned by PipeliningDispatcher#apply
 * are masked, and will only propagate the interrupt if the future doesn't
 * return after 10 seconds after the interruption.  This ensures that
 * interrupting a Future in one request won't change the result of another
 * request unless the connection is stuck, and does not look like it will make
 * progress.
 *
 * @param statsReceiver typically scoped to `clientName/dispatcher`
 */
abstract class GenPipeliningDispatcher[Req, Rep, In, Out, T](
    trans: Transport[In, Out],
    statsReceiver: StatsReceiver,
    stallTimeout: Duration,
    timer: Timer)
  extends GenSerialClientDispatcher[Req, Rep, In, Out](
    trans,
    statsReceiver) { self =>
  import GenPipeliningDispatcher._

  // thread-safety provided by synchronization on this
  private[this] var stalled = false
  private[this] val q = new AsyncQueue[Pending[T, Rep]]

  private[this] val queueSize =
    statsReceiver.scope("pipelining").addGauge("pending") {
      q.size
    }

  private[this] val transRead: Pending[T, Rep] => Unit =
    p =>
      trans.read().respond { out =>
        try respond(p.value, p.promise, out)
        finally loop()
      }

  private[this] def loop(): Unit =
    q.poll().onSuccess(transRead)

  loop()

  /**
    * Handle the server response `out` given the corresponding element `value`
    * enqueued during dispatch.
    *
    * This typically involves fulfilling `p` with a function of `(T, Try[Out]) => Rep`
    *
    * @param value the corresponding element returned by `pipeline` during dispatch
    * @param p the promise to fulfill the rpc
    * @param out the server response
    */
  protected def respond(value: T, p: Promise[Rep], out: Try[Out]): Unit

  /**
    * Send a request `req` to the server and provide a value `T` to insert into the
    * pipeline queue. The value is provided back to `respond` to handle the corresponding
    * request.
    *
    * @param req the request to send
    * @param p the promise to fulfill when the request is handled.
    * @return a value associated with `req` that is handed back during response handling.
    */
  protected def pipeline(req: Req, p: Promise[Rep]): Future[T]

  // Dispatch serialization is guaranteed by GenSerialClientDispatcher so we
  // leverage that property to sequence `q` offers.
  protected def dispatch(req: Req, p: Promise[Rep]): Future[Unit] =
    pipeline(req, p).flatMap { toQueue => q.offer(Pending(toQueue, p)); Future.Done }

  override def apply(req: Req): Future[Rep] = {
    val f = super.apply(req)
    val p = Promise[Rep]()
    f.proxyTo(p)

    p.setInterruptHandler {
      case t: Throwable =>
        timer.schedule(Time.now + stallTimeout) {
          if (!f.isDefined) {
            f.raise(stalledPipelineException(stallTimeout))
            self.synchronized {
              // we check stalled so that we log exactly once per failed pipeline
              if (!stalled) {
                stalled = true
                val addr = trans.remoteAddress
                GenPipeliningDispatcher.log.warning(
                  s"pipelined connection stalled with ${q.size} items, talking to $addr")
              }
            }
          }
        }
    }
    p
  }
}

object GenPipeliningDispatcher {
  val log = Logger.get(getClass.getName)

  private case class Pending[T, Rep](value: T, promise: Promise[Rep])

  def stalledPipelineException(timeout: Duration) =
    Failure(
      s"The connection pipeline could not make progress in $timeout",
      Failure.Interrupted)

  object Timeout {
    def unapply(t: Throwable): Option[Throwable] = t match {
      case exc: com.twitter.util.TimeoutException => Some(exc)
      case exc: com.twitter.finagle.TimeoutException => Some(exc)
      case _ => None
    }
  }
}

case class StalledPipelineTimeout(timeout: Duration) {
  def mk(): (StalledPipelineTimeout, Stack.Param[StalledPipelineTimeout]) =
    (this, StalledPipelineTimeout.param)
}
object StalledPipelineTimeout {
  implicit val param = Stack.Param(StalledPipelineTimeout(timeout = 10.seconds))
}

class PipeliningDispatcher[Req, Rep](trans: Transport[Req, Rep],
  statsReceiver: StatsReceiver,
  stallTimeout: Duration,
  timer: Timer) extends GenPipeliningDispatcher[Req, Rep, Req, Rep, Unit](trans, statsReceiver, stallTimeout, timer) {

  final override protected def respond(value: Unit, p: Promise[Rep], out: Try[Rep]): Unit =
    p.updateIfEmpty(out)

  final override protected def pipeline(req: Req, p: Promise[Rep]): Future[Unit] =
    trans.write(req).map(_ => p)
}
