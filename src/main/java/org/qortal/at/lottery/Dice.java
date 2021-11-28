package org.qortal.at.lottery;

import org.ciyam.at.*;
import org.qortal.at.QortalFunctionCode;

import java.nio.ByteBuffer;

import static org.ciyam.at.OpCode.calcOffset;

/**
 * Design goals:
 *  1. Sleep until we receive a message/payment to avoid extra DB state records
 *  2. Any MESSAGE from creator causes AT to refund balance to creator and finish
 *  3. Enforce minimum PAYMENT (below minimum payments simply ignored)
 *  4. PAYMENTs larger than 6x balance are simply refunded
 *
 * Data:
 *      [start timestamp / most recent transaction timestamp]
 *      [cutoff timestamp]
 *      [number of valid entries: set to 0]
 *      [best distance (unsigned): set to max]
 *      [best winner]
 *
 * Code:
 *      record start time
 *
 *      Loop:
 *          sleep until message/payment
 *          fetch next transaction
 *          update most recent transaction timestamp
 *          if message:
 *              if not from creator, continue loop
 *              refund balance to creator and finish
 *          if payment:
 *              if amount less than minimum, continue loop
 *              if amount greater than 6x balance, refund to sender, continue loop
 *          generate random
 *          if loser:
 *              continue loop
 *          send amount x6 to sender
 *          continue loop
 */
public class Dice {

    private static byte[] CODE_BYTES;

    /** SHA256 of AT code bytes */
    private static byte[] CODE_BYTES_HASH;

    /** Potential fees incurred by AT before paying out. Used as a safety margin to make sure AT has enough to pay out winner. */
    private static final long PAYOUT_FEES = 100_0000L;

    /**
     * Returns Qortal AT creation bytes for dice AT.
     *
     * @param minimumAmount     Minimum amount of QORT for valid entry
     */
    public static byte[] buildQortalAT(long minimumAmount) {
        if (minimumAmount < 100_0000L || minimumAmount > 1000_0000_0000L)
            throw new IllegalArgumentException("Minimum amount should be between 0.01 QORT and 1000 QORT");

        // Labels for data segment addresses
        int addrCounter = 0;

        final int addrMinimumAmount = addrCounter++;
        final int addrLastTxnTimestamp = addrCounter++;

        final int addrPreviousBlockHash = addrCounter; addrCounter += 4;

        final int addrResult = addrCounter++;
        final int addrTxnType = addrCounter++;
        final int addrPaymentTxnType = addrCounter++;
        final int addrPaymentAmount = addrCounter++;
        final int addrCurrentBalance = addrCounter++;
        final int addrWinningPayout = addrCounter++;
        final int addrZero = addrCounter++;
        final int addrSix = addrCounter++;

        /*
         * We SHA256 bytes in data segment from zero to addrSenderAddress (inclusive) for each entry to produce random.
         */
        final int addrSenderAddress = addrCounter; addrCounter += 4;
        final int addrSenderAddressByteLength = addrCounter++;

        // Data segment
        ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);

        // Minimum accepted amount
        dataByteBuffer.position(addrMinimumAmount * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(minimumAmount);

        // Number of data segment bytes from start to include addrSenderAddress
        dataByteBuffer.position(addrSenderAddressByteLength * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(addrSenderAddressByteLength * MachineState.VALUE_SIZE);

        // PAYMENT transaction type
        dataByteBuffer.position(addrPaymentTxnType * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(API.ATTransactionType.PAYMENT.value);

        // SIX!
        dataByteBuffer.position(addrSix * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(6L);

        // Code labels
        Integer labelSleepLoop = null;
        Integer labelTxnLoop = null;
        Integer labelCheckTxn2 = null;
        Integer labelRollDice = null;
        Integer labelPayout = null;

        ByteBuffer codeByteBuffer = ByteBuffer.allocate(768);

        // Two-pass version
        for (int pass = 0; pass < 2; ++pass) {
            codeByteBuffer.clear();

            try {
                /* Initialization */

                // Use AT creation 'timestamp' as starting point for finding transactions sent to AT
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxnTimestamp));

                /* MAIN LOOP */
                labelSleepLoop = codeByteBuffer.position();

                /* Sleep */
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.SLEEP_UNTIL_MESSAGE.value, addrLastTxnTimestamp));
                /* Done sleeping */

                /* Transaction processing loop */
                labelTxnLoop = codeByteBuffer.position();

                // Find next transaction (if any) to this AT since the last one (referenced by addrLastTxnTimestamp)
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxnTimestamp));
                // If no transaction found, A will be zero. If A is zero, set addrResult to 1, otherwise 0.
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrResult));
                // If addrResult is non-zero (i.e. A is zero, transaction not found) then go back to sleep
                codeByteBuffer.put(OpCode.BNZ_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelSleepLoop)));

                /* Check transaction */

                // Update our 'last found transaction's timestamp' using 'timestamp' from transaction
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, addrLastTxnTimestamp));
                // Extract sender address from transaction into B register
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));

                // Extract transaction type (message/payment) from transaction and save type in addrTxnType
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, addrTxnType));
                // If transaction type is PAYMENT type then perform further checks
                codeByteBuffer.put(OpCode.BEQ_DAT.compile(addrTxnType, addrPaymentTxnType, calcOffset(codeByteBuffer, labelCheckTxn2)));

                // MESSAGE transaction

                // Move sender address from B to A
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.SWAP_A_AND_B));
                // Copy creator address into B
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_CREATOR_INTO_B));
                // Is sender (A) the same as creator (B)?
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_EQUALS_B, addrResult));
                // If addrResult is zero / false (i.e. A != B) then go find another transaction
                codeByteBuffer.put(OpCode.BZR_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelTxnLoop)));

                // Creator requests finish - which also refunds balance back to creator
                codeByteBuffer.put(OpCode.FIN_IMD.compile());


                /* Check transaction - part 2 */
                labelCheckTxn2 = codeByteBuffer.position();

                // Check payment amount is at least minimum amount
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_AMOUNT_FROM_TX_IN_A, addrPaymentAmount));
                // If payment amount is too small, go find another transaction
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrPaymentAmount, addrMinimumAmount, calcOffset(codeByteBuffer, labelTxnLoop)));

                // Check payment amount isn't too large
                // Calculate potential payout
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrWinningPayout, addrPaymentAmount));
                codeByteBuffer.put(OpCode.MUL_VAL.compile(addrWinningPayout, 6));
                // Find current balance
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CURRENT_BALANCE, addrCurrentBalance));
                // Subtract potential fees
                codeByteBuffer.put(OpCode.SUB_VAL.compile(addrCurrentBalance, PAYOUT_FEES));
                // Not too much? - go to roll the dice
                codeByteBuffer.put(OpCode.BGT_DAT.compile(addrCurrentBalance, addrWinningPayout, calcOffset(codeByteBuffer, labelRollDice)));

                // Too much - refund back to sender
                // B should still contain sender's address
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrPaymentAmount));
                // Try another transaction
                codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTxnLoop == null ? 0 : labelTxnLoop));

                // ROLL THE DICE!
                labelRollDice = codeByteBuffer.position();

                // Save sender address
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.GET_B_DAT, addrSenderAddress));
                // Also use block hash
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_PREVIOUS_BLOCK_HASH_INTO_A));
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.GET_A_DAT, addrPreviousBlockHash));
                // SHA256 to spread sender's chances across entire 256 bits
                codeByteBuffer.put(OpCode.EXT_FUN_DAT_2.compile(FunctionCode.SHA256_INTO_B, addrZero, addrSenderAddressByteLength));
                // Extract some of the hash output
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B4, addrResult));
                // Modulo 6
                codeByteBuffer.put(OpCode.MOD_DAT.compile(addrResult, addrSix));

                // Winner if result is zero
                codeByteBuffer.put(OpCode.BZR_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelPayout)));
                // Didn't win
                codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTxnLoop == null ? 0 : labelTxnLoop));

                /* WINNER! Pay arranged amount to receiving address */
                labelPayout = codeByteBuffer.position();

                // Load B register with winner's address
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.SET_B_DAT, addrSenderAddress));
                // Pay winning payout to receiving address
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrWinningPayout));
                // Try another transaction
                codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTxnLoop == null ? 0 : labelTxnLoop));
            } catch (CompilationException e) {
                throw new IllegalStateException("Unable to compile AT?", e);
            }
        }

        codeByteBuffer.flip();

        byte[] codeBytes = new byte[codeByteBuffer.limit()];
        codeByteBuffer.get(codeBytes);

        final short ciyamAtVersion = 2;
        final short numCallStackPages = 0;
        final short numUserStackPages = 0;
        final long minActivationAmount = 0L;

        return MachineState.toCreationBytes(ciyamAtVersion, codeBytes, dataByteBuffer.array(), numCallStackPages, numUserStackPages, minActivationAmount);
    }

}
