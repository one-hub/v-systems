package vsys

import java.io.File
import java.security.SecureRandom

import org.h2.mvstore.MVStore

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.reflect.runtime.universe
import scala.util._

package object utils extends ScorexLogging {

  @tailrec
  final def untilTimeout[T](timeout: FiniteDuration,
                            delay: FiniteDuration = 100.milliseconds,
                            onFailure: => Unit = {})(fn: => T): T = {
    Try {
      fn
    } match {
      case Success(x) => x
      case _ if timeout > delay =>
        Thread.sleep(delay.toMillis)
        untilTimeout(timeout - delay, delay, onFailure)(fn)
      case Failure(e) =>
        onFailure
        throw e
    }
  }

  def randomBytes(howMany: Int = 32): Array[Byte] = {
    val r = new Array[Byte](howMany)
    new SecureRandom().nextBytes(r) //overrides r
    r
  }

  def objectFromString[T](fullClassName: String): Try[T] = Try {
    val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)
    val module = runtimeMirror.staticModule(fullClassName)
    val obj = runtimeMirror.reflectModule(module)
    obj.instance.asInstanceOf[T]
  }

  def base58Length(byteArrayLength: Int): Int = math.ceil(math.log(256) / math.log(58) * byteArrayLength).toInt

  def createMVStore(file: Option[File], encryptionKey: Option[Array[Char]] = None): MVStore = {
    val builder = file.fold(new MVStore.Builder) { p =>
      p.getParentFile.mkdirs()
      new MVStore.Builder()
        .fileName(p.getCanonicalPath)
        .autoCommitDisabled()
        .compress()
    }

    val store = encryptionKey match {
      case Some(key) => builder.encryptionKey(key).open()
      case _ => builder.open()
    }

    store.rollback()

    store
  }

  def forceStopApplication(): Unit = new Thread(() => { System.exit(1) }, "vsys-shutdown-thread").start()
}
