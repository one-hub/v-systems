package vsys.blockchain.block

import com.google.common.primitives.{Bytes, Ints, Longs}
import play.api.libs.json.{JsObject, Json}
import vsys.account.{Address, PrivateKeyAccount, PublicKeyAccount}
import vsys.blockchain.state.{ByteStr, Diff}
import vsys.blockchain.transaction.TransactionParser._
import vsys.blockchain.transaction.ValidationError.GenericError
import vsys.blockchain.transaction._
import vsys.settings.GenesisSettings
import vsys.utils.crypto.EllipticCurveImpl
import vsys.utils.ScorexLogging

import scala.util.{DynamicVariable, Failure, Try}

case class Block(timestamp: Long, version: Byte, reference: ByteStr, signerData: SignerData,
                 consensusData: SposConsensusBlockData, transactionData: Seq[ProcessedTransaction]) extends Signed {

  private lazy val versionField: ByteBlockField = ByteBlockField("version", version)
  private lazy val timestampField: LongBlockField = LongBlockField("timestamp", timestamp)
  private lazy val referenceField: BlockIdField = BlockIdField("reference", reference.arr)
  private lazy val signerDataField: SignerDataBlockField = SignerDataBlockField("signature", signerData)
  private lazy val consensusDataField = SposConsensusBlockField(consensusData)
  private lazy val transactionDataField = TransactionsBlockField(version.toInt, transactionData)
  private lazy val transactionMerkleField = MerkleRootBlockField("TransactionMerkleRoot", transactionData)

  private val resourcePricingData = ResourcePricingBlockData(0L, 0L, 0L, 0L, 0L)
  private lazy val resourcePricingDataField = ResourcePricingBlockField(resourcePricingData)

  lazy val uniqueId: ByteStr = signerData.signature

  lazy val fee: Long = transactionData.map(_.transaction.transactionFee).sum

  lazy val json: JsObject =
    versionField.json ++
      timestampField.json ++
      referenceField.json ++
      consensusDataField.json ++
      resourcePricingDataField.json ++
      transactionMerkleField.json ++
      transactionDataField.json ++
      signerDataField.json ++
      Json.obj(
        "fee" -> fee,
        "blocksize" -> bytes.length
      )

  lazy val bytes: Array[Byte] = {
    val txBytesSize = transactionDataField.bytes.length
    val txBytes = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transactionDataField.bytes

    val cBytesSize = consensusDataField.bytes.length
    val cBytes = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ consensusDataField.bytes

    val fBytesSize = resourcePricingDataField.bytes.length
    val fBytes = Bytes.ensureCapacity(Ints.toByteArray(fBytesSize), 4, 0) ++ resourcePricingDataField.bytes

    versionField.bytes ++
      timestampField.bytes ++
      referenceField.bytes ++
      cBytes ++
      fBytes ++
      transactionMerkleField.bytes ++
      txBytes ++
      signerDataField.bytes
  }

  lazy val bytesWithoutSignature: Array[Byte] = bytes.dropRight(SignatureLength)

  // set blockScore to minting Balance / 100000000
  val sc = Math.max(consensusData.mintBalance / 100000000L, 1L)
  lazy val blockScore: BigInt = BigInt(sc.toString)

  // destroy transaction fee
  lazy val feesDistribution: Diff = Diff.empty

  override lazy val signatureValid: Boolean = EllipticCurveImpl.verify(signerData.signature.arr, bytesWithoutSignature, signerData.generator.publicKey)
  override lazy val signedDescendants: Seq[Signed] = transactionData.map(_.transaction)
}


object Block extends ScorexLogging {
  type BlockIds = Seq[ByteStr]
  type BlockId = ByteStr

  val MaxTransactionsPerBlockVer1: Int = 100
  val MaxTransactionsPerBlockVer2: Int = 65535
  val MintTimeLength: Int = 8
  val MintBalanceLength: Int = 8
  val GeneratorSignatureLength: Int = 32

  val BlockIdLength = SignatureLength
  val TransactionMerkleRootLength: Int = 32
  val TransactionSizeLength = 4

  def transParseBytes(version: Int,bytes: Array[Byte]): Try[Seq[ProcessedTransaction]] = Try {
    if (bytes.isEmpty ) {
       Seq.empty
      } else {
        val v: (Array[Byte], Int) = version match {
        case 1 | 2 => (bytes.tail        , bytes.head) //  255  max
        case _ => ???
      }

      (1 to v._2).foldLeft((0: Int, Seq[ProcessedTransaction]())) { case ((pos, txs), _) =>
          val transactionLengthBytes = v._1.slice(pos, pos + TransactionSizeLength)
          val transactionLength = Ints.fromByteArray(transactionLengthBytes)
          val transactionBytes = v._1.slice(pos + TransactionSizeLength, pos + TransactionSizeLength + transactionLength)
          val transaction = ProcessedTransactionParser.parseBytes(transactionBytes).get

        (pos + TransactionSizeLength + transactionLength, txs :+ transaction)
      }._2
    }
  }

  def parseBytes(bytes: Array[Byte]): Try[Block] = Try {

    val version = bytes.head

    val positionVal = new DynamicVariable(1)
    def position = positionVal.value

    val timestamp = Longs.fromByteArray(bytes.slice(position, position + 8))
    positionVal.value = position + 8

    val reference = ByteStr(bytes.slice(position, position + SignatureLength))
    positionVal.value = position + SignatureLength

    val cBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    positionVal.value = position + 4
    val cBytes = bytes.slice(position, position + cBytesLength)
    val mintTimeBytes = cBytes.slice(0, Block.MintTimeLength)
    val mintBalanceBytes = cBytes.slice(Block.MintTimeLength, Block.MintTimeLength + Block.MintBalanceLength)
    val consData = SposConsensusBlockData(Longs.fromByteArray(mintTimeBytes), Longs.fromByteArray(mintBalanceBytes))
    positionVal.value = position + cBytesLength

    val fBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    positionVal.value = position + 4
    positionVal.value = position + fBytesLength

    positionVal.value = position + TransactionMerkleRootLength

    val tBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    positionVal.value = position + 4
    val tBytes = bytes.slice(position, position + tBytesLength)
    val txBlockField = transParseBytes(version.toInt, tBytes).get
    positionVal.value = position + tBytesLength

    val genPK = bytes.slice(position, position + KeyLength)
    positionVal.value = position + KeyLength

    val signature = ByteStr(bytes.slice(position, position + SignatureLength))

    Block(timestamp, version, reference, SignerData(PublicKeyAccount(genPK), signature), consData, txBlockField)
  }.recoverWith { case t: Throwable =>
    log.error("Error when parsing block", t)
    Failure(t)
  }

  def buildAndSign(version: Byte,
                   timestamp: Long,
                   reference: ByteStr,
                   consensusData: SposConsensusBlockData,
                   transactionData: Seq[ProcessedTransaction],
                   signer: PrivateKeyAccount): Block = {
    val nonSignedBlock = Block(timestamp, version, reference, SignerData(signer, ByteStr.empty), consensusData,
      transactionData)
    val toSign = nonSignedBlock.bytes
    val signature = EllipticCurveImpl.sign(signer, toSign)
    require(reference.arr.length == SignatureLength, "Incorrect reference")
    require(signer.publicKey.length == KeyLength, "Incorrect signer.publicKey")
    nonSignedBlock.copy(signerData = SignerData(signer, ByteStr(signature)))
  }

  def genesisTransactions(gs: GenesisSettings): Seq[GenesisTransaction] = {
    gs.transactions.map { ts =>
      val acc = Address.fromString(ts.recipient).right.get
      GenesisTransaction.create(acc, ts.amount, ts.slotId, gs.timestamp).right.get
    }
  }

  def genesis(genesisSettings: GenesisSettings): Either[ValidationError, Block] = {
    val version: Byte = 1

    val genesisSigner = PrivateKeyAccount(Array.empty)

    val transactionGenesisData = genesisTransactions(genesisSettings).map {
      tx => ProcessedTransaction(TransactionStatus.Success, tx.transactionFee, tx)
    }
    val transactionGenesisDataField = TransactionsBlockFieldVersion1or2(transactionGenesisData)
    // initial minting Balance set as 0
    val consensusGenesisData = SposConsensusBlockData(genesisSettings.initialMintTime, 0L)
    val consensusGenesisDataField = SposConsensusBlockField(consensusGenesisData)
    val feeGenesisData = ResourcePricingBlockData(0L, 0L, 0L, 0L, 0L)
    val feeGenesisDataField = ResourcePricingBlockField(feeGenesisData)
    val txBytesSize = transactionGenesisDataField.bytes.length
    val txBytes = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transactionGenesisDataField.bytes
    val cBytesSize = consensusGenesisDataField.bytes.length
    val cBytes = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ consensusGenesisDataField.bytes
    val fBytesSize = feeGenesisDataField.bytes.length
    val fBytes = Bytes.ensureCapacity(Ints.toByteArray(fBytesSize), 4, 0) ++ feeGenesisDataField.bytes
    val genesisTransactionMerkleBytes = MerkleRootBlockField("TransactionMerkleRoot", transactionGenesisData).bytes

    val reference = Array.fill(SignatureLength)(-1: Byte)

    val timestamp = genesisSettings.blockTimestamp
    val toSign: Array[Byte] = Array(version) ++
      Bytes.ensureCapacity(Longs.toByteArray(timestamp), 8, 0) ++
      reference ++
      cBytes ++
      fBytes ++
      genesisTransactionMerkleBytes ++
      txBytes ++
      genesisSigner.publicKey

    val signature = genesisSettings.signature.fold(EllipticCurveImpl.sign(genesisSigner, toSign))(_.arr)

    if (EllipticCurveImpl.verify(signature, toSign, genesisSigner.publicKey))
      Right(Block(timestamp = timestamp,
        version = version,
        reference = ByteStr(reference),
        signerData = SignerData(genesisSigner, ByteStr(signature)),
        consensusData = consensusGenesisData,
        transactionData = transactionGenesisData))
    else Left(GenericError("Passed genesis signature is not valid"))

  }
}
