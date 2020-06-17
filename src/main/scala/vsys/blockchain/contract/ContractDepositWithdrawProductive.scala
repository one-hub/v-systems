package vsys.blockchain.contract

import com.google.common.primitives.Ints
import vsys.blockchain.contract.ContractGen._
import vsys.blockchain.state._
import vsys.utils.serialization.Deser

object ContractDepositWithdrawProductive {
  lazy val contract: Contract = Contract.buildContract(Deser.serilizeString("vdds"), Ints.toByteArray(1),
    Seq(initTrigger, depositTrigger, withdrawTrigger), Seq(),
    Seq(makerStateVar.arr, tokenIdStateVar.arr), Seq(), Seq(triggerTextual, descriptorTextual, stateVarTextual)
  ).explicitGet()

  //statevar
  val stateVarName = List("maker", "tokenId")
  val makerStateVar = StateVar(0.toByte, DataType.Address.id.toByte)
  val tokenIdStateVar = StateVar(1.toByte, DataType.TokenId.id.toByte)
  lazy val stateVarTextual: Array[Byte] = Deser.serializeArrays(stateVarName.map(x => Deser.serilizeString(x)))

  //initTrigger
  val initId: Short = 0
  val initPara: Seq[String] = Seq("tokenId", "signer")
  val initDataType: Array[Byte] = Array(DataType.TokenId.id.toByte)
  val initOpcs: Seq[Array[Byte]] = Seq(
    loadSigner ++ Array(1.toByte),
    cdbvSet ++ Array(makerStateVar.index, 1.toByte),
    cdbvSet ++ Array(tokenIdStateVar.index, 0.toByte))
  val initTrigger: Array[Byte] = getFunctionBytes(initId, onInitTriggerType, nonReturnType, initDataType, initOpcs)
  val initFuncBytes: Array[Byte] = textualFunc("init", Seq(), initPara)

  //depositTrigger
  val depositId: Short = 1
  val depositPara: Seq[String] = Seq("sender", "amount", "tokenId", "contractTokenId")
  val depositDataType: Array[Byte] = Array(DataType.Account.id.toByte, DataType.Amount.id.toByte, DataType.TokenId.id.toByte)
  val depositOpcs: Seq[Array[Byte]] = Seq(
    assertCaller ++ Array(0.toByte),
    cdbvrGet ++ Array(tokenIdStateVar.index, 3.toByte),
    assertEqual ++ Array(2.toByte, 3.toByte))
  val depositTrigger: Array[Byte] = getFunctionBytes(depositId, onDepositTriggerType, nonReturnType, depositDataType, depositOpcs)
  val depositFuncBytes: Array[Byte] = textualFunc("deposit", Seq(), depositPara)

  //withdrawTrigger
  val withdrawId: Short = 2
  val withdrawPara: Seq[String] = Seq("recipient", "amount", "tokenId", "contractTokenId")
  val withdrawDataType: Array[Byte] = Array(DataType.Account.id.toByte, DataType.Amount.id.toByte, DataType.TokenId.id.toByte)
  val withdrawOpcs: Seq[Array[Byte]] = Seq(
    assertCaller ++ Array(0.toByte),
    cdbvrGet ++ Array(tokenIdStateVar.index, 3.toByte),
    assertEqual ++ Array(2.toByte, 3.toByte))
  val withdrawTrigger: Array[Byte] = getFunctionBytes(withdrawId, onWithDrawTriggerType, nonReturnType, withdrawDataType, withdrawOpcs)
  val withdrawFuncBytes: Array[Byte] = textualFunc("withdraw", Seq(), withdrawPara)

  //Textual
  lazy val triggerTextual: Array[Byte] = Deser.serializeArrays(Seq(initFuncBytes, depositFuncBytes, withdrawFuncBytes))
  lazy val descriptorTextual: Array[Byte] = Deser.serializeArrays(Seq())

}