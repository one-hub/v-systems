package com.wavesplatform.generator

import java.io.File
import java.net.InetSocketAddress

import com.google.common.base.CaseFormat
import com.typesafe.config.{Config, ConfigFactory}
import vsys.settings.loadConfig
import net.ceedubs.ficus.Ficus._
import org.slf4j.LoggerFactory
import vsys.account.PrivateKeyAccount
import vsys.utils.crypto.encode.Base58
import vsys.blockchain.transaction.TransactionParser
import vsys.blockchain.transaction.TransactionParser.TransactionType
import vsys.utils.LoggerFacade

import scala.concurrent.duration.FiniteDuration

case class GeneratorSettings(chainId: Char,
                             accounts: Seq[PrivateKeyAccount],
                             n: Int,
                             every: FiniteDuration,
                             txProbabilities: Map[TransactionParser.TransactionType.Value, Float],
                             sendTo: InetSocketAddress)

object GeneratorSettings {
  val configPath: String = "generator"

  def fromConfig(config: Config): GeneratorSettings = {
    val converter = CaseFormat.LOWER_HYPHEN.converterTo(CaseFormat.UPPER_CAMEL)
    def toTxType(key: String): TransactionType.Value =
      TransactionType.withName(s"${converter.convert(key)}Transaction")

    val chainId = config.as[String](s"$configPath.chainId").head
    val accounts = config.as[List[String]](s"$configPath.accounts").map(s => PrivateKeyAccount(Base58.decode(s).get))
    val n = config.as[Int](s"$configPath.n")
    val every = config.as[FiniteDuration](s"$configPath.every")
    val txProbabilities = config.as[Map[String, Double]](s"$configPath.probabilities").map(kv => toTxType(kv._1) -> kv._2.toFloat)
    val sendTo = new InetSocketAddress(config.as[String](s"$configPath.send-to.address"), config.as[Int](s"$configPath.send-to.port"))

    GeneratorSettings(chainId, accounts, n, every, txProbabilities, sendTo)
  }

  private val log = LoggerFacade(LoggerFactory.getLogger(getClass))

  def readConfig(userConfigPath: Option[String]): Config = {
    val maybeConfigFile = for {
      maybeFilename <- userConfigPath
      file = new File(maybeFilename)
      if file.exists
    } yield file

    val config = maybeConfigFile match {
      // if no user config is supplied, the library will handle overrides/application/reference automatically
      case None =>
        ConfigFactory.load()
      // application config needs to be resolved wrt both system properties *and* user-supplied config.
      case Some(file) =>
        val cfg = ConfigFactory.parseFile(file)
        if (!cfg.hasPath("generator")) {
          log.error("Malformed configuration file was provided! Aborting!")
          System.exit(1)
        }
        loadConfig(cfg)
    }

    config
  }
}
