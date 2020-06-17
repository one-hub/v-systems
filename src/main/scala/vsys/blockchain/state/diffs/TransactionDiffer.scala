package vsys.blockchain.state.diffs

import vsys.blockchain.state.reader.StateReader
import vsys.blockchain.state.Diff
import vsys.blockchain.transaction._
import vsys.blockchain.transaction.contract.{ExecuteContractFunctionTransaction, RegisterContractTransaction}
import vsys.blockchain.transaction.database.DbPutTransaction
import vsys.blockchain.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import vsys.blockchain.transaction.spos.{ContendSlotsTransaction, ReleaseSlotsTransaction}
import vsys.blockchain.transaction.ValidationError.UnsupportedTransactionType
import vsys.settings.FunctionalitySettings

object TransactionDiffer {

  case class TransactionValidationError(cause: ValidationError, tx: Transaction) extends ValidationError

  def apply(settings: FunctionalitySettings, prevBlockTimestamp: Option[Long], currentBlockTimestamp: Long, currentBlockHeight: Int)(s: StateReader, tx: Transaction): Either[ValidationError, Diff] = {
    for {
      t0 <- Signed.validateSignatures(tx)
      t1 <- CommonValidation.disallowTxFromFuture(currentBlockTimestamp, t0)
      t2 <- CommonValidation.disallowTxFromPast(prevBlockTimestamp, t1)
      t3 <- CommonValidation.disallowBeforeActivationHeight(settings, currentBlockHeight, t2)
      t4 <- CommonValidation.disallowDuplicateIds(s, settings, currentBlockHeight, t3)
      t5 <- CommonValidation.disallowSendingGreaterThanBalance(s, settings, currentBlockTimestamp, t4)
      t6 <- CommonValidation.disallowInvalidFeeScale(t5)
      t7 <- t6 match {
        case pt: ProvenTransaction => CommonValidation.disallowProofsCountOverflow(pt)
        case t => Right(t)
      }
      diff <- t7 match {
        case gtx: GenesisTransaction => GenesisTransactionDiff(settings, currentBlockHeight)(gtx)
        case ptx: PaymentTransaction => PaymentTransactionDiff(s, currentBlockHeight, settings, currentBlockTimestamp)(ptx)
        case ltx: LeaseTransaction => LeaseTransactionsDiff.lease(s, currentBlockHeight)(ltx)
        case ltx: LeaseCancelTransaction => LeaseTransactionsDiff.leaseCancel(s, settings, currentBlockTimestamp, currentBlockHeight)(ltx)
        case mtx: MintingTransaction => MintingTransactionDiff(s, currentBlockHeight, settings, currentBlockTimestamp)(mtx)
        case cstx: ContendSlotsTransaction => ContendSlotsTransactionDiff(s, settings, currentBlockHeight)(cstx)
        case rstx: ReleaseSlotsTransaction => ReleaseSlotsTransactionDiff(s, settings, currentBlockHeight)(rstx)
        case rctx: RegisterContractTransaction => RegisterContractTransactionDiff(s, settings, currentBlockHeight, prevBlockTimestamp, currentBlockTimestamp)(rctx)
        case ectx: ExecuteContractFunctionTransaction => ExecuteContractFunctionTransactionDiff(s, settings, currentBlockHeight, prevBlockTimestamp, currentBlockTimestamp)(ectx)
        case dptx: DbPutTransaction => DbTransactionDiff(s, currentBlockHeight)(dptx)
        case _ => Left(UnsupportedTransactionType)
      }
      positiveDiff <- BalanceDiffValidation(s, currentBlockTimestamp)(diff)
      positiveTokenDiff <- TokenBalanceDiffValidation(s, currentBlockTimestamp)(positiveDiff)
    } yield positiveTokenDiff
  }.left.map(TransactionValidationError(_, tx))
}
