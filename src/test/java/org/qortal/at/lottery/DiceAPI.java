package org.qortal.at.lottery;

import org.ciyam.at.*;
import org.ciyam.at.test.TestAPI;

import java.util.List;

public class DiceAPI extends TestAPI {

    protected Long sleepUntilMessageTimestamp;

    public Long getSleepUntilMessageTimestamp() {
        return this.sleepUntilMessageTimestamp;
    }

    public void setSleepUntilMessageTimestamp(Long sleepUntilMessageTimestamp) {
        this.sleepUntilMessageTimestamp = sleepUntilMessageTimestamp;
    }

    public boolean willExecute(MachineState state, int blockHeight) {
        // Sleep-until-message/height checking
        Long sleepUntilMessageTimestamp = this.getSleepUntilMessageTimestamp();

        if (sleepUntilMessageTimestamp != null) {
            // Quicker to check height, if sleep-until-height also active
            Integer sleepUntilHeight = state.getSleepUntilHeight();

            boolean wakeDueToHeight = sleepUntilHeight != null && sleepUntilHeight != 0 && blockHeight >= sleepUntilHeight;

            boolean wakeDueToMessage = false;
            if (!wakeDueToHeight) {
                // No avoiding asking repository
                Timestamp previousTxTimestamp = new Timestamp(sleepUntilMessageTimestamp);
                TestTransaction nextTransaction = this.getTransactionAfterTimestamp(blockHeight, previousTxTimestamp, state);
                wakeDueToMessage = nextTransaction != null;
            }

            // Can we skip?
            if (!wakeDueToHeight && !wakeDueToMessage)
                return false;
        }

        return true;
    }

    public void preExecute(MachineState state) {
        // Sleep-until-message/height checking
        Long sleepUntilMessageTimestamp = this.getSleepUntilMessageTimestamp();

        if (sleepUntilMessageTimestamp != null) {
            // We've passed checks, so clear sleep-related flags/values
            this.setIsSleeping(state, false);
            this.setSleepUntilHeight(state, 0);
            this.setSleepUntilMessageTimestamp(null);
        }
    }

    @Override
    public void platformSpecificPreExecuteCheck(int paramCount, boolean returnValueExpected, MachineState state, short rawFunctionCode)
            throws IllegalFunctionCodeException {
        DiceFunctionCode qortalFunctionCode = DiceFunctionCode.valueOf(rawFunctionCode);

        if (qortalFunctionCode == null)
            throw new IllegalFunctionCodeException("Unknown Qortal function code 0x" + String.format("%04x", rawFunctionCode) + " encountered");

        qortalFunctionCode.preExecuteCheck(paramCount, returnValueExpected, rawFunctionCode);
    }

    @Override
    public void platformSpecificPostCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
        DiceFunctionCode qortalFunctionCode = DiceFunctionCode.valueOf(rawFunctionCode);

        if (qortalFunctionCode == null)
            throw new IllegalFunctionCodeException("Unknown Qortal function code 0x" + String.format("%04x", rawFunctionCode) + " encountered");

        qortalFunctionCode.execute(functionData, state, rawFunctionCode);
    }

    /*package*/ void sleepUntilMessageOrHeight(MachineState state, long txTimestamp, Long sleepUntilHeight) {
        this.setIsSleeping(state, true);

        this.setSleepUntilMessageTimestamp(txTimestamp);

        if (sleepUntilHeight != null)
            this.setSleepUntilHeight(state, sleepUntilHeight.intValue());
    }

    private TestTransaction getTransactionAfterTimestamp(int currentBlockHeight, Timestamp timestamp, MachineState state) {
        int blockHeight = timestamp.blockHeight;
        int transactionSequence = timestamp.transactionSequence + 1;

        while (blockHeight <= currentBlockHeight) {
            TestBlock block = this.blockchain.get(blockHeight - 1);

            List<TestTransaction> transactions = block.transactions;

            if (transactionSequence >= transactions.size()) {
                // No more transactions at this height
                ++blockHeight;
                transactionSequence = 0;
                continue;
            }

            TestTransaction transaction = transactions.get(transactionSequence);

            if (transaction.recipient.equals("AT")) {
                // Found a transaction
                System.out.println(String.format("Found transaction at height %d, sequence %d: %s %s from %s",
                        blockHeight,
                        transactionSequence,
                        transaction.txType.equals(ATTransactionType.PAYMENT) ? prettyAmount(transaction.amount) : "",
                        transaction.txType.name(),
                        transaction.sender
                ));

                return transaction;
            }

            ++transactionSequence;
        }

        // Nothing found
        return null;
    }

}
