package vsys.settings

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.EnumerationReader._

object LogLevel extends Enumeration {
  val TRACE = Value("TRACE")
  val DEBUG = Value("DEBUG")
  val INFO = Value("INFO")
  val WARN = Value("WARN")
  val ERROR = Value("ERROR")
}

case class VsysSettings(directory: String,
                        dataDirectory: String,
                        loggingLevel: LogLevel.Value,
                        networkSettings: NetworkSettings,
                        walletSettings: WalletSettings,
                        blockchainSettings: BlockchainSettings,
                        checkpointsSettings: CheckpointsSettings,
                        feesSettings: FeesSettings,
                        minerSettings: MinerSettings,
                        restAPISettings: RestAPISettings,
                        synchronizationSettings: SynchronizationSettings,
                        utxSettings: UtxSettings)

object VsysSettings {
  import NetworkSettings.networkSettingsValueReader

  val configPath: String = "vsys"
  def fromConfig(config: Config): VsysSettings = {
    val directory = config.as[String](s"$configPath.directory")
    val dataDirectory = config.as[String](s"$configPath.data-directory")
    val loggingLevel = config.as[LogLevel.Value](s"$configPath.logging-level")

    val networkSettings = config.as[NetworkSettings]("vsys.network")
    val walletSettings = config.as[WalletSettings]("vsys.wallet")
    val blockchainSettings = BlockchainSettings.fromConfig(config)
    val checkpointsSettings = CheckpointsSettings.fromConfig(config)
    val feesSettings = FeesSettings.fromConfig(config)
    val minerSettings = config.as[MinerSettings]("vsys.miner")
    val restAPISettings = RestAPISettings.fromConfig(config)
    val synchronizationSettings = SynchronizationSettings.fromConfig(config)
    val utxSettings = config.as[UtxSettings]("vsys.utx")

    VsysSettings(directory, dataDirectory, loggingLevel, networkSettings, walletSettings, blockchainSettings, checkpointsSettings,
      feesSettings, minerSettings, restAPISettings, synchronizationSettings, utxSettings)
  }
}
