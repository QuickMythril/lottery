package org.qortal.at.lottery;

import org.ciyam.at.*;

import java.nio.ByteBuffer;

import static org.ciyam.at.OpCode.calcOffset;

/**
 * Design goals:
 *  1. Sleep for set period to avoid extra DB state records
 *  2. Enforce minimum entry price (excess ignored, or even welcomed)
 *  3. Multiple entries by same account should not increase chances of winning
 *  4. Entries after reawakening are not considered although funds are added to prize
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
        if (sleepMinutes < 10 || sleepMinutes > 60 * 24 * 30)
            throw new IllegalArgumentException("Sleep period should be between 10 minutes and 1 month");

        if (minimumAmount < 1000000L || minimumAmount > 100000000000L)
            throw new IllegalArgumentException("Minimum amount should be between 0.01 QORT and 1000 QORT");

        // Labels for data segment addresses
        int addrCounter = 0;

        final int addrSleepMinutes = addrCounter++;
        final int addrMinimumAmount = addrCounter++;

        final int addrSleepUntilHeight = addrCounter++;
        final int addrCurrentBlockTimestamp = addrCounter++;

        final int addrWinningValue = addrCounter; addrCounter += 4;
        final int addrWinningValuePointer = addrCounter++;

        /*
         * Values before addrCurrentAddress must not change once we start checking for winners.
         * We SHA256 bytes in data segment from zero to addrCurrentAddressPointer (inclusive) for each entry.
         * The same 'address' must produce the same hash!
         */
        final int addrCurrentAddress = addrCounter; addrCounter += 4;
        final int addrCurrentAddressPointer = addrCounter++;
        final int addrCurrentAddressByteLength = addrCounter++;

        final int addrLastTxnTimestamp = addrCounter++;
        final int addrResult = addrCounter++;
        final int addrTxnType = addrCounter++;
        final int addrPaymentTxnType = addrCounter++;
        final int addrPaymentAmount = addrCounter++;

        final int addrNumberOfEntries = addrCounter++;

        final int addrCurrentDistance = addrCounter; addrCounter += 4;
        final int addrCurrentDistancePointer = addrCounter++;

        final int addrTopBitShift = addrCounter++;
        final int addrAllButTopBitMask = addrCounter++;
        final int addrCurrentDistanceTemp = addrCounter++;
        final int addrBestDistanceTemp = addrCounter++;

        final int addrBestDistance = addrCounter; addrCounter += 4;
        final int addrBestDistancePointer = addrCounter++;

        final int addrBestAddress = addrCounter; addrCounter += 4;
        final int addrBestAddressPointer = addrCounter++;

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

        // Winning value pointer
        dataByteBuffer.position(addrWinningValuePointer * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(addrWinningValue);

        // Current address pointer
        dataByteBuffer.position(addrCurrentAddressPointer * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(addrCurrentAddress);

        // Number of data segment bytes from start to include addrCurrentAddressPointer
        dataByteBuffer.position(addrCurrentAddressByteLength * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(addrCurrentAddressByteLength);

        // PAYMENT transaction type
        dataByteBuffer.position(addrPaymentTxnType * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(API.ATTransactionType.PAYMENT.value);

        // Current distance pointer
        dataByteBuffer.position(addrCurrentDistancePointer * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(addrCurrentDistance);

        // Bit masks
        dataByteBuffer.position(addrTopBitShift * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(63L);
        dataByteBuffer.position(addrAllButTopBitMask * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(0x7FFFFFFFFFFFFFFFL);

        // Best distance (initialized to MAX UNSIGNED)
        dataByteBuffer.position(addrBestDistance * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(0xFFFFFFFFFFFFFFFFL);
        dataByteBuffer.putLong(0xFFFFFFFFFFFFFFFFL);
        dataByteBuffer.putLong(0xFFFFFFFFFFFFFFFFL);
        dataByteBuffer.putLong(0xFFFFFFFFFFFFFFFFL);

        // Best distance pointer
        dataByteBuffer.position(addrBestDistancePointer * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(addrBestDistance);

        // Best address pointer
        dataByteBuffer.position(addrBestAddressPointer * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(addrBestAddress);

        // Data segment byte length (for SHA256)
        dataByteBuffer.position(addrDataSegmentByteLength * MachineState.VALUE_SIZE);
        dataByteBuffer.putLong(addrCounter);

        // Code labels
        Integer labelDoneSleeping = null;
        Integer labelTxnLoop = null;
        Integer labelCheckTxn = null;
        Integer labelCheckTxn2 = null;
        Integer labelWorseDistance = null;
        Integer labelBetterDistance = null;
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
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrBestAddressPointer));

                /*
                 * This is nasty!
                 * We want to sleep until a certain height, and SLP_DAT expects an integer block height.
                 * However, we can only fetch current block 'timestamp' which has block height in upper 32 bits.
                 * So we have to shift-right to extract block height.
                 * Even worse, there's no SHR_IMD so we have to store the shift amount in another data field!
                 */
                // Save current block 'timestamp' into addrSleepUntilHeight
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_BLOCK_TIMESTAMP, addrSleepUntilHeight));
                // Add number of minutes to sleep (assuming roughly 1 block per minute)
                codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, addrSleepUntilHeight, addrSleepUntilHeight, addrSleepMinutes));

                /* Sleeping loop */

                // Restart after this opcode
                codeByteBuffer.put(OpCode.SET_PCS.compile());

                // Save current block 'timestamp' into addrCurrentBlockTimestamp
                codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_BLOCK_TIMESTAMP, addrCurrentBlockTimestamp));
                // Not slept enough?
                codeByteBuffer.put(OpCode.BGE_DAT.compile(addrCurrentBlockTimestamp, addrSleepUntilHeight, calcOffset(codeByteBuffer, labelDoneSleeping)));
                // Nope: wait for next block (restarts at PCS, set above)
                codeByteBuffer.put(OpCode.STP_IMD.compile());

                /* Done sleeping */
                labelDoneSleeping = codeByteBuffer.position();

                // Generate winning value
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_PREVIOUS_BLOCK_HASH_INTO_A));
                // Save block hash into addrWinningValue1-4
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_A_IND, addrWinningValuePointer));
                // Now SHA256 all data segment from start to finish. Hash will be in B
                codeByteBuffer.put(OpCode.EXT_FUN_DAT_2.compile(FunctionCode.SHA256_INTO_B, addrZero, addrDataSegmentByteLength));
                // Save SHA256 hash into addrWinningvalue1-4
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrWinningValuePointer));

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
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrLastTxnTimestamp, addrSleepUntilHeight, calcOffset(codeByteBuffer, labelCheckTxn2)));
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
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrCurrentAddressPointer));
                // SHA256 to spread sender's chances across entire 256 bits
                codeByteBuffer.put(OpCode.EXT_FUN_DAT_2.compile(FunctionCode.SHA256_INTO_B, addrZero, addrCurrentAddressByteLength));

                // Subtract sender's value from winning value as distance
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrCurrentDistancePointer));
                codeByteBuffer.put(OpCode.SUB_DAT.compile(addrCurrentDistance + 0, addrWinningValue + 0));
                codeByteBuffer.put(OpCode.SUB_DAT.compile(addrCurrentDistance + 1, addrWinningValue + 1));
                codeByteBuffer.put(OpCode.SUB_DAT.compile(addrCurrentDistance + 2, addrWinningValue + 2));
                codeByteBuffer.put(OpCode.SUB_DAT.compile(addrCurrentDistance + 3, addrWinningValue + 3));

                // Unsigned comparison to see if this distance is less than best distance

                /*
                 * For each of the four longs:
                 *  Compare most significant bit first
                 *      If best's bit is 0 and current's is 1 then current is worse
                 *      If best's bit is 1 and current's is 0 then current is better
                 *  Otherwise, compare remaining bits as normal after zeroing top bit
                 */
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistanceTemp, addrBestDistance + 0));
                codeByteBuffer.put(OpCode.SHR_DAT.compile(addrBestDistanceTemp, addrTopBitShift));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrCurrentDistanceTemp, addrCurrentDistance + 0));
                codeByteBuffer.put(OpCode.SHR_DAT.compile(addrCurrentDistanceTemp, addrTopBitShift));
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelWorseDistance)));
                codeByteBuffer.put(OpCode.BGT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelBetterDistance)));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistanceTemp, addrBestDistance + 0));
                codeByteBuffer.put(OpCode.AND_DAT.compile(addrBestDistanceTemp, addrAllButTopBitMask));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrCurrentDistanceTemp, addrCurrentDistance + 0));
                codeByteBuffer.put(OpCode.AND_DAT.compile(addrCurrentDistanceTemp, addrAllButTopBitMask));
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelWorseDistance)));
                codeByteBuffer.put(OpCode.BGT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelBetterDistance)));
                // fall-through if equal
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistanceTemp, addrBestDistance + 1));
                codeByteBuffer.put(OpCode.SHR_DAT.compile(addrBestDistanceTemp, addrTopBitShift));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrCurrentDistanceTemp, addrCurrentDistance + 1));
                codeByteBuffer.put(OpCode.SHR_DAT.compile(addrCurrentDistanceTemp, addrTopBitShift));
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelWorseDistance)));
                codeByteBuffer.put(OpCode.BGT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelBetterDistance)));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistanceTemp, addrBestDistance + 1));
                codeByteBuffer.put(OpCode.AND_DAT.compile(addrBestDistanceTemp, addrAllButTopBitMask));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrCurrentDistanceTemp, addrCurrentDistance + 1));
                codeByteBuffer.put(OpCode.AND_DAT.compile(addrCurrentDistanceTemp, addrAllButTopBitMask));
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelWorseDistance)));
                codeByteBuffer.put(OpCode.BGT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelBetterDistance)));
                // fall-through if equal
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistanceTemp, addrBestDistance + 2));
                codeByteBuffer.put(OpCode.SHR_DAT.compile(addrBestDistanceTemp, addrTopBitShift));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrCurrentDistanceTemp, addrCurrentDistance + 2));
                codeByteBuffer.put(OpCode.SHR_DAT.compile(addrCurrentDistanceTemp, addrTopBitShift));
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelWorseDistance)));
                codeByteBuffer.put(OpCode.BGT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelBetterDistance)));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistanceTemp, addrBestDistance + 2));
                codeByteBuffer.put(OpCode.AND_DAT.compile(addrBestDistanceTemp, addrAllButTopBitMask));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrCurrentDistanceTemp, addrCurrentDistance + 2));
                codeByteBuffer.put(OpCode.AND_DAT.compile(addrCurrentDistanceTemp, addrAllButTopBitMask));
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelWorseDistance)));
                codeByteBuffer.put(OpCode.BGT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelBetterDistance)));
                // fall-through if equal
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistanceTemp, addrBestDistance + 3));
                codeByteBuffer.put(OpCode.SHR_DAT.compile(addrBestDistanceTemp, addrTopBitShift));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrCurrentDistanceTemp, addrCurrentDistance + 3));
                codeByteBuffer.put(OpCode.SHR_DAT.compile(addrCurrentDistanceTemp, addrTopBitShift));
                codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelWorseDistance)));
                codeByteBuffer.put(OpCode.BGT_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelBetterDistance)));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrBestDistanceTemp, addrBestDistance + 3));
                codeByteBuffer.put(OpCode.AND_DAT.compile(addrBestDistanceTemp, addrAllButTopBitMask));
                codeByteBuffer.put(OpCode.SET_DAT.compile(addrCurrentDistanceTemp, addrCurrentDistance + 3));
                codeByteBuffer.put(OpCode.AND_DAT.compile(addrCurrentDistanceTemp, addrAllButTopBitMask));
                codeByteBuffer.put(OpCode.BLE_DAT.compile(addrBestDistanceTemp, addrCurrentDistanceTemp, calcOffset(codeByteBuffer, labelWorseDistance))); // Note BLE for last long
                // no need for BGT
                codeByteBuffer.put(OpCode.JMP_ADR.compile(labelBetterDistance));

                // Not winner
                labelWorseDistance = codeByteBuffer.position();

                // Try another transaction
                codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTxnLoop));

                // New current winner
                labelBetterDistance = codeByteBuffer.position();

                // Save new winner address
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrBestAddressPointer));
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
                codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrBestAddressPointer));
                // Pay AT's balance to receiving address
                codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PAY_ALL_TO_ADDRESS_IN_B));
                // We're finished forever (finishing auto-refunds remaining balance to AT creator)
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
