package vsys.settings

import java.time.Duration

import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import org.scalatest.{FlatSpec, Matchers}

class MinerSettingsSpecification extends FlatSpec with Matchers {
  "MinerSettings" should "read values" in {
    val config = ConfigFactory.parseString(
      """
        |vsys {
        |  miner {
        |    enable: yes
        |    quorum: 1
        |    interval-after-last-block-then-generation-is-allowed: 1d
        |    reward-address: ""
        |  }
        |}
      """.stripMargin).resolve()

    val settings = config.as[MinerSettings]("vsys.miner")

    settings.enable should be(true)
    settings.quorum should be(1)
    settings.intervalAfterLastBlockThenGenerationIsAllowed should be(Duration.ofDays(1))
    settings.rewardAddress should be("")
  }
}
