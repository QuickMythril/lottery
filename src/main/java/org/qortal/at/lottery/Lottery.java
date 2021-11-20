package org.qortal.at.lottery;

import org.ciyam.at.*;

import java.nio.ByteBuffer;

import static org.ciyam.at.OpCode.calcOffset;

/**
 * Design goals:
 *  1. Sleep for set period to avoid extra DB state records
 *  2. Enforce minimum entry price (below minimum payments simply ignored)
 *  3. Multiple entries by same account should not increase chances of winning
 *  4. Entries after reawakening are not considered (although funds are added to prize)
 *  5. No entries cause AT funds to be returned to creator
 *  6. Winner should not be known before reawakening cutoff!
 *
 * Data:
 *      [start timestamp / most recent transaction timestamp]
 *      [cutoff timestamp]
 *      [number of valid entries: set to 0]
 *      [best distance (unsigned): set to max]
 *      [best winner]
 *
 * Code:
 *      set best winner to creator in case of no entries
 *      record start time
 *      sleep
 *      record cutoff time
 *      generate random win value
 *
 *      Loop:
 *          fetch next transaction
 *          if none, go to payout
 *          if after cutoff time, go to payout
 *          update most recent transaction timestamp
 *          if amount less than minimum, continue loop
 *          increment number of valid entries
 *          extract transaction's sender (address / public key?)
 *          subtract from win value to derive 'distance' -- UNSIGNED
 *          if result greater-or-equal to best distance, continue loop -- UNSIGNED
 *          save new best distance
 *          save new best winner
 *          continue loop
 *
 *      Payout:
 *          send balance to 'best winner'
 */
public class Lottery {

    private static byte[] CODE_BYTES;

    /** SHA256 of AT code bytes */
    private static byte[] CODE_BYTES_HASH;

    /**
     * Returns Qortal AT creation bytes for lottery AT.
     *
     * @param sleepMinutes      Time period for allowing entries (roughly 1 block per minute)
     * @param minimumAmount     Minimum amount of QORT for valid entry
     */
    public static byte[] buildQortalAT(int sleepMinutes, long minimumAmount) {
        if (sleepMinutes < 10 || sleepMinutes > 30 * 24 * 60)
            throw new IllegalArgumentException("Sleep period should be between 10 minutes and 1 month");

        if (minimumAmount < 100_0000L || minimumAmount > 1000_0000_0000L)
            throw new IllegalArgumentException("Minimum amount should be between 0.01 QORT and 1000 QORT");

        // Labels for data segment addresses
        int addrCounter = 0;

        final int addrSleepMinutes = addrCounter++;
        final int addrMinimumAmount = addrCounter++;

        final int addrSleepUntilTimestamp = addrCounter++;
        final int addrSleepUntilHeight = addrCounter++;

        final int addrWinningValue = addrCounter; addrCounter += 4;

        /*
         * Values before addrCurrentAddress must not change once we start checking for winners.
         * We SHA256 bytes in data segment from zero to addrCurrentAddress (inclusive) for each entry.
         * The same 'address' must produce the same hash!
         */
        final int addrCurrentAddress = addrCounter; addrCounter += 4;
        final int addrCurrentAddressByteLength = addrCounter++;

        final int addrLastTxnTimestamp = addrCounter++;
        final int addrResult = addrCounter++;
        final int addrTxnType = addrCounter++;
        final int addrPaymentTxnType = addrCounter++;
        final int addrPaymentAmount = addrCounter++;

        final int addrNumberOfEntries = addrCounter++;

        final int addrCurrentDistance = addrCounter; addrCounter += 4;

        final int addrBestDistance = addrCounter; addrCounter += 4;
        final int addrBestAddress = addrCounter; addrCounter += 4;

        final int addrZero = addrCounter++;
        final int addrDataSegmentByteLength = addrCounter++;

        // Data segment
        ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);

        // Sleep period (minutes)
        dataByteBuffer.position(addrSleepMinutes * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(sleepMinutes);

        // Minimum accepted amount
        dataByteBuffer.position(addrMinimumAmount * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(minimumAmount);

        // Number of data segment bytes from start to include addrCurrentAddress
        dataByteBuffer.position(addrCurrentAddressByteLength * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(addrCurrentAddressByteLength * MachineState.VALUE_SIZE);

        // PAYMENT transaction type
        dataByteBuffer.position(addrPaymentTxnType * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(API.ATTransactionType.PAYMENT.value);

        // Best distance (initialized to MAX UNSIGNED)
        dataByteBuffer.position(addrBestDistance * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(0xFFFFFFFFFFFFFFFFL);
        dataByteBuffer.putLong(0xFFFFFFFFFFFFFFFFL);
        dataByteBuffer.putLong(0xFFFFFFFFFFFFFFFFL);
        dataByteBuffer.putLong(0xFFFFFFFFFFFFFFFFL);

        // Data segment byte length (for SHA256)
        dataByteBuffer.position(addrDataSegmentByteLength * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(addrCounter * MachineState.VALUE_SIZE);

        // Code labels
        Integer labelTxnLoop = null;
        Integer labelCheckTxn = null;
        Integer labelCheckTxn2 = null;
        Integer labelNewWinner = null;
        Integer labelPayout = null;

        ByteBuffer codeByteBuffer = ByteBuffer.allocate(768);

        // Two-pass version
        for (int pass = 0; pass < 2; ++pass) {
            codeByteBuffer.clear();

            try {
                /* Initialization */

                // Use AT creation 'timestamp' as starting point for finding transactions sent to AT
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxnTimestamp));

                // Load B register with AT creator's address so we can save it into addrBestAddress1-4
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_CREATOR_INTO_B));
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.GET_B_DAT, addrBestAddress));

                /*
                 * We want to sleep for a while.
                 *
                 * We could use SLP_VAL but different sleep periods would produce different code hashes,
                 * which would make identifying similar lottery ATs more difficult.
                 *
                 * Instead we add sleepMinutes (as block count) to current block height,
                 * which is in the upper 32 bits of current block 'timestamp',
                 * so we perform a shift-right to extract.
                 */
                // Save current block 'timestamp' into addrSleepUntilHeight
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_BLOCK_TIMESTAMP, addrSleepUntilTimestamp));
                // Add number of minutes to sleep (assuming roughly 1 block per minute)
                codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, addrSleepUntilTimestamp, addrSleepUntilTimestamp, addrSleepMinutes));
                // Copy then shift-right to convert 'timestamp' to block height
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrSleepUntilHeight, addrSleepUntilTimestamp));
                codeByteBuffer.put(OpCode.SHR_VAL.compile(addrSleepUntilHeight, 32L));

                /* Sleep */
                codeByteBuffer.put(OpCode.SLP_DAT.compile(addrSleepUntilHeight));

                /* Done sleeping */

                // Generate winning value
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_PREVIOUS_BLOCK_HASH_INTO_A));
                // Save block hash into addrWinningValue1-4
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.GET_A_DAT, addrWinningValue));
                // Now SHA256 all data segment from start to finish. Hash will be in B
                codeByteBuffer.put(OpCode.EXT_FUN_DAT_2.compile(FunctionCode.SHA256_INTO_B, addrZero, addrDataSegmentByteLength));
                // Save SHA256 hash into addrWinningvalue1-4
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.GET_B_DAT, addrWinningValue));

                /* Transaction processing loop */

                // Restart after this opcode (probably not needed, but just in case)
                codeByteBuffer.put(OpCode.SET_PCS.compile());

                labelTxnLoop = codeByteBuffer.position();

                // Find next transaction (if any) to this AT since the last one (referenced by addrLastTxnTimestamp)
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxnTimestamp));
                // If no transaction found, A will be zero. If A is zero, set addrResult to 1, otherwise 0.
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrResult));
                // If addrResult is zero (i.e. A is non-zero, transaction was found) then go check transaction
                codeByteBuffer.put(OpCode.BZR_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelCheckTxn)));
                // No (more) transactions found - jump to payout
                codeByteBuffer.put(OpCode.JMP_ADR.compile(labelPayout == null ? 0 : labelPayout));

                /* Check transaction */
                labelCheckTxn = codeByteBuffer.position();

                // Update our 'last found transaction's timestamp' using 'timestamp' from transaction
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, addrLastTxnTimestamp));

                // If transaction is before cut-off timestamp then perform more checks
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrLastTxnTimestamp, addrSleepUntilTimestamp, calcOffset(codeByteBuffer, labelCheckTxn2)));
                // Past cut-off - jump to payout
                codeByteBuffer.put(OpCode.JMP_ADR.compile(labelPayout == null ? 0 : labelPayout));

                /* Check transaction - part 2 */
                labelCheckTxn2 = codeByteBuffer.position();

                // Extract transaction type (message/payment) from transaction and save type in addrTxnType
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, addrTxnType));
                // If transaction type is not PAYMENT type then go look for another transaction
                codeByteBuffer.put(OpCode.BNE_DAT.compile(addrTxnType, addrPaymentTxnType, calcOffset(codeByteBuffer, labelTxnLoop)));

                // Check payment amount is at least minimum amount
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_AMOUNT_FROM_TX_IN_A, addrPaymentAmount));
                // If payment amount is too small, go find another transaction
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrPaymentAmount, addrMinimumAmount, calcOffset(codeByteBuffer, labelTxnLoop)));

                // Bump count of valid entries!
                codeByteBuffer.put(OpCode.INC_DAT.compile(addrNumberOfEntries));

                // Calculate 'distance' of sender's address from winning value

                // Extract sender address from transaction into B register
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
                // Save sender address
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.GET_B_DAT, addrCurrentAddress));
                // SHA256 to spread sender's chances across entire 256 bits
                codeByteBuffer.put(OpCode.EXT_FUN_DAT_2.compile(FunctionCode.SHA256_INTO_B, addrZero, addrCurrentAddressByteLength));

                // Subtract sender's value from winning value as distance
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.GET_B_DAT, addrCurrentDistance));
                codeByteBuffer.put(OpCode.SUB_DAT.compile(addrCurrentDistance + 0, addrWinningValue + 0));
                codeByteBuffer.put(OpCode.SUB_DAT.compile(addrCurrentDistance + 1, addrWinningValue + 1));
                codeByteBuffer.put(OpCode.SUB_DAT.compile(addrCurrentDistance + 2, addrWinningValue + 2));
                codeByteBuffer.put(OpCode.SUB_DAT.compile(addrCurrentDistance + 3, addrWinningValue + 3));

                // Copy current entry's distance into A
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.SET_A_DAT, addrCurrentDistance));

                // Copy best distance into B
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.SET_B_DAT, addrBestDistance));

                // Unsigned comparison to see if this distance is less than best distance
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.UNSIGNED_COMPARE_A_WITH_B, addrResult));

                // If result is -1 then we have a new current winner
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrResult, addrZero, calcOffset(codeByteBuffer, labelNewWinner)));

                // Try another transaction
                codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTxnLoop));

                // New current winner
                labelNewWinner = codeByteBuffer.position();

                // Save new winner address
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestAddress + 0, addrCurrentAddress + 0));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestAddress + 1, addrCurrentAddress + 1));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestAddress + 2, addrCurrentAddress + 2));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestAddress + 3, addrCurrentAddress + 3));
                // Save new best distance
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistance + 0, addrCurrentDistance + 0));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistance + 1, addrCurrentDistance + 1));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistance + 2, addrCurrentDistance + 2));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistance + 3, addrCurrentDistance + 3));
                // Try another transaction
                codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTxnLoop));

                /* Success! Pay arranged amount to receiving address */
                labelPayout = codeByteBuffer.position();

                // Load B register with winner's address
                codeByteBuffer.put(OpCode.EXT_FUN_VAL.compile(FunctionCode.SET_B_DAT, addrBestAddress));
                // Pay AT's balance to receiving address
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PAY_ALL_TO_ADDRESS_IN_B));
                // We're finished forever
                codeByteBuffer.put(OpCode.FIN_IMD.compile());
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
