package com.wavesplatform.it.network

import java.nio.charset.StandardCharsets

import com.wavesplatform.it._
import com.wavesplatform.it.api.NodeApi
import vsys.network.{RawBytes, TransactionMessageSpec}
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import vsys.account.{Address, PrivateKeyAccount}
import scorex.crypto.encode.Base58
import vsys.blockchain.transaction.PaymentTransaction

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.traverse
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

class SimpleTransactionsSuite extends FunSuite with BeforeAndAfterAll with Matchers with ScalaFutures
  with IntegrationPatience with RecoverMethods with RequestErrorAssert with IntegrationNodesInitializationAndStopping
  with IntegrationTestsScheme {

  override val docker = new Docker()

  override val nodes = Docker.NodeConfigs.getConfigList("nodes").asScala.take(3).map(docker.startNode)
  val node = nodes.head

  test("valid tx send by network to node should be in blockchain") {
    val tx = PaymentTransaction.create(
      PrivateKeyAccount(Base58.decode(node.accountSeed).get),
      Address.fromString(node.address).right.get,
      1L,
      100000L,
      100,
      System.currentTimeMillis(),
      Array()).right.get
    val f = for {
      _ <- node.sendByNetwork(RawBytes(TransactionMessageSpec.messageCode, tx.bytes))
      _ <- Future.successful(Thread.sleep(2000))

      height <- traverse(nodes)(_.height).map(_.max)
      _ <- traverse(nodes)(_.waitForHeight(height + 1))
      tx <- node.waitForTransaction(tx.id.base58)
    } yield {
      tx shouldBe NodeApi.Transaction(tx.`type`, tx.id, tx.fee, tx.timestamp)
    }
    Await.result(f, 60.seconds)
  }

  test("invalid tx send by network to node should be not in UTX or blockchain") {
    val tx = PaymentTransaction.create(
      PrivateKeyAccount(Base58.decode(node.accountSeed).get),
      Address.fromString(node.address).right.get,
      1L,
      100000L,
      100,
      System.currentTimeMillis() + (1 days).toMillis,
      Array()).right.get
    val f = for {
      _ <- node.sendByNetwork(RawBytes(TransactionMessageSpec.messageCode, tx.bytes))
      _ <- Future.successful(Thread.sleep(2000))
      _ <- Future.sequence(nodes.map(_.ensureTxDoesntExist(tx.id.base58)))
    } yield ()
    Await.result(f, 60.seconds)
  }

  test("should blacklist senders of non-parsable transactions") {
    val f = for {
      blacklistBefore <- node.blacklistedPeers
      _ <- node.sendByNetwork(RawBytes(TransactionMessageSpec.messageCode, "foobar".getBytes(StandardCharsets.UTF_8)))
      _ <- Future.successful(Thread.sleep(2000))
      blacklistAfter <- node.blacklistedPeers
    } yield blacklistAfter.size should be > blacklistBefore.size
    Await.result(f, 60.seconds)
  }
}
