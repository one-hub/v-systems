package vsys.blockchain.contract

import vsys.account.{ContractAccount, PublicKeyAccount}
import vsys.blockchain.state.reader.{CompositeStateReader, StateReader}
import vsys.blockchain.transaction.{ProvenTransaction, ValidationError}
import vsys.blockchain.transaction.ValidationError.{GenericError, InvalidContractAddress, InvalidFunctionIndex}
import vsys.blockchain.transaction.contract.{ExecuteContractFunctionTransaction, RegisterContractTransaction}
import vsys.blockchain.transaction.proof.EllipticCurve25519Proof
import vsys.blockchain.state.opcdiffs.OpcDiff
import vsys.settings.FunctionalitySettings
import vsys.utils.serialization.Deser

case class ExecutionContext(signers: Seq[PublicKeyAccount],
                            state: StateReader,
                            settings: FunctionalitySettings,
                            prevBlockTimestamp: Option[Long],
                            currentBlockTimestamp: Long,
                            height: Int,
                            transaction: ProvenTransaction,
                            contractId: ContractAccount,
                            opcFunc: Array[Byte],
                            stateVar: Seq[Array[Byte]],
                            stateMap: Seq[Array[Byte]],
                            description: Array[Byte],
                            depth: Int) {

}

object ExecutionContext {

  def fromRegConTx(s: StateReader,
                   settings: FunctionalitySettings,
                   prevBlockTimestamp: Option[Long],
                   currentBlockTimestamp: Long,
                   height: Int,
                   tx: RegisterContractTransaction): Either[ValidationError, ExecutionContext] = {
    val signers = tx.proofs.proofs.map(x => EllipticCurve25519Proof.fromBytes(x.bytes.arr).toOption.get.publicKey)
    val contractId = tx.contractId
    val opcFunc = tx.contract.trigger.find(a => (a.length > 2) && (a(2) == 0.toByte)).getOrElse(Array[Byte]())
    val stateVar = tx.contract.stateVar
    val stateMap = tx.contract.stateMap
    val description = Deser.serilizeString(tx.description)
    Right(ExecutionContext(signers, s, settings, prevBlockTimestamp, currentBlockTimestamp, height,
                           tx, contractId, opcFunc, stateVar, stateMap, description, 0))
  }

  def fromExeConTx(s: StateReader,
                   settings: FunctionalitySettings,
                   prevBlockTimestamp: Option[Long],
                   currentBlockTimestamp: Long,
                   height: Int,
                   tx: ExecuteContractFunctionTransaction): Either[ValidationError, ExecutionContext] = {
    val signers = tx.proofs.proofs.map(x => EllipticCurve25519Proof.fromBytes(x.bytes.arr).toOption.get.publicKey)
    val contractId = tx.contractId
    val description = tx.attachment
    if (contractId.bytes.arr sameElements ContractAccount.systemContractId.bytes.arr) {
      if (tx.funcIdx >= 0 && tx.funcIdx < ContractSystem.contract.descriptor.length) {
        Right(ExecutionContext(signers, s, settings, prevBlockTimestamp, currentBlockTimestamp,
                               height, tx, contractId, ContractSystem.contract.descriptor(tx.funcIdx),
                               ContractSystem.contract.stateVar, ContractSystem.contract.stateMap, description, 0))
      } else {
        Left(InvalidFunctionIndex)
      }
    } else {
      s.contractContent(tx.contractId.bytes) match {
        case Some((_, _, contract)) if tx.funcIdx >=0 && tx.funcIdx < contract.descriptor.length =>
          Right(ExecutionContext(signers, s, settings, prevBlockTimestamp, currentBlockTimestamp, height, tx, contractId,
            contract.descriptor(tx.funcIdx), contract.stateVar, contract.stateMap, description, 0))
        case Some(_) => Left(InvalidFunctionIndex)
        case _ => Left(InvalidContractAddress)
      }
    }
  }

  def fromCallOpc(c: ExecutionContext,
                  diff: OpcDiff,
                  contractId: ContractAccount,
                  callType: CallType.Value, // trigger or function
                  callIndex: Int // trigger type or function index
                 ): Either[ValidationError, ExecutionContext] = {
    val state = new CompositeStateReader(c.state, diff.asBlockDiff(c.height, c.transaction))
    if (c.depth == 1) {
      Left(GenericError("stack overflow"))
    }
    state.contractContent(contractId.bytes) match {
      case Some((_, _, contract)) => if (callType == CallType.Trigger) {
        val opcFunc = contract.trigger.find(a => (a.length > 2) && (a(2) == callIndex.toByte))
        if (opcFunc.isDefined) {
          Right(ExecutionContext(c.signers, state, c.settings, c.prevBlockTimestamp, c.currentBlockTimestamp,
                                 c.height, c.transaction, contractId, opcFunc.get,
                                 contract.stateVar, contract.stateMap, c.description, c.depth + 1))
        } else {
          Left(GenericError("no such trigger"))
        }
      } else if (callType == CallType.Function) {
        if (callIndex >= 0 && callIndex < contract.descriptor.length) {
          Right(ExecutionContext(c.signers, state, c.settings, c.prevBlockTimestamp, c.currentBlockTimestamp,
                                 c.height, c.transaction, contractId, contract.descriptor(callIndex),
                                 contract.stateVar, contract.stateMap, c.description, c.depth + 1))
        } else {
          Left(GenericError("invalid contract function index"))
        }
      } else {
        Left(GenericError("invalid call type"))
      }
      case _ => Left(InvalidContractAddress)
    }
  }

}
