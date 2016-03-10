package fluflu

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels._
import java.net.{InetSocketAddress, StandardSocketOptions}
import java.lang.{Boolean => JBool}
import java.util.concurrent.TimeUnit

import scala.concurrent.{Future, Promise}

object Channel {
  private[fluflu] val channelMap = scala.collection.concurrent.TrieMap[String, Channel]()
  def apply(host: String, port: Int, timeout: Int): Channel =
    channelMap getOrElseUpdate (s"$host-$port", new Channel(host, port, timeout: Int))
}

class Channel(host: String, port: Int, timeout: Int) {

  import Channel._
  import StandardSocketOptions._

  private[this] val remote = new InetSocketAddress(host, port)

  private[this] var channel = AsynchronousSocketChannel open()

  def connect(reset: Boolean = false): Unit = channel synchronized {
    if (reset) {
      channel = AsynchronousSocketChannel.open()
    } else {
      channel setOption[JBool](SO_REUSEADDR, true)
      channel setOption[JBool](SO_KEEPALIVE, true)
      (channel connect remote) get
    }
  }

  def write(buf: ByteBuffer): Future[Int] = {
    val p = Promise[Int]
    try {
      channel write(
        buf,
        timeout,
        TimeUnit.MILLISECONDS,
        (),
        new CompletionHandler[Integer, Unit] {
          override def completed(result: Integer, attachment: Unit): Unit = p.success(result)

          override def failed(exc: Throwable, attachment: Unit): Unit = {
            exc match {
              case e: IOException => connect(true)
            }
            p.failure(exc)
          }
        }
      )
    } catch {
      case e: Throwable => p.failure(e)
    }
    p.future
  }

  def close(): Unit = {
    channel close()
    channelMap remove s"$host-$port"
  }

}
