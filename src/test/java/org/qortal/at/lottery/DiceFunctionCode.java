package org.qortal.at.lottery;

import org.ciyam.at.ExecutionException;
import org.ciyam.at.FunctionData;
import org.ciyam.at.IllegalFunctionCodeException;
import org.ciyam.at.MachineState;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Qortal-specific CIYAM-AT Functions.
 * <p>
 * Function codes need to be between 0x0500 and 0x06ff.
 *
 */
public enum DiceFunctionCode {
    /**
     * Sleep AT until a new message arrives after 'tx-timestamp'.<br>
     * <tt>0x0503 tx-timestamp</tt>
     */
    SLEEP_UNTIL_MESSAGE(0x0503, 1, false) {
        @Override
        protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
            if (functionData.value1 <= 0)
                return;

            long txTimestamp = functionData.value1;

            DiceAPI api = (DiceAPI) state.getAPI();
            api.sleepUntilMessageOrHeight(state, txTimestamp, null);
        }
    },
    /**
     * Sleep AT until a new message arrives, after 'tx-timestamp', or height reached.<br>
     * <tt>0x0504 tx-timestamp height</tt>
     */
    SLEEP_UNTIL_MESSAGE_OR_HEIGHT(0x0504, 2, false) {
        @Override
        protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
            if (functionData.value1 <= 0)
                return;

            long txTimestamp = functionData.value1;

            if (functionData.value2 <= 0)
                return;

            long sleepUntilHeight = functionData.value2;

            DiceAPI api = (DiceAPI) state.getAPI();
            api.sleepUntilMessageOrHeight(state, txTimestamp, sleepUntilHeight);
        }
    };

    public final short value;
    public final int paramCount;
    public final boolean returnsValue;

    private static final Map<Short, DiceFunctionCode> map = Arrays.stream(DiceFunctionCode.values())
            .collect(Collectors.toMap(functionCode -> functionCode.value, functionCode -> functionCode));

    private DiceFunctionCode(int value, int paramCount, boolean returnsValue) {
        this.value = (short) value;
        this.paramCount = paramCount;
        this.returnsValue = returnsValue;
    }

    public static DiceFunctionCode valueOf(int value) {
        return map.get((short) value);
    }

    public void preExecuteCheck(int paramCount, boolean returnValueExpected, short rawFunctionCode) throws IllegalFunctionCodeException {
        if (paramCount != this.paramCount)
            throw new IllegalFunctionCodeException(
                    "Passed paramCount (" + paramCount + ") does not match function's required paramCount (" + this.paramCount + ")");

        if (returnValueExpected != this.returnsValue)
            throw new IllegalFunctionCodeException(
                    "Passed returnValueExpected (" + returnValueExpected + ") does not match function's return signature (" + this.returnsValue + ")");
    }

    /**
     * Execute Function
     * <p>
     * Can modify various fields of <tt>state</tt>, including <tt>programCounter</tt>.
     * <p>
     * Throws a subclass of <tt>ExecutionException</tt> on error, e.g. <tt>InvalidAddressException</tt>.
     *
     * @param functionData
     * @param state
     * @throws ExecutionException
     */
    public void execute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
        // Check passed functionData against requirements of this function
        preExecuteCheck(functionData.paramCount, functionData.returnValueExpected, rawFunctionCode);

        if (functionData.paramCount >= 1 && functionData.value1 == null)
            throw new IllegalFunctionCodeException("Passed value1 is null but function has paramCount of (" + this.paramCount + ")");

        if (functionData.paramCount == 2 && functionData.value2 == null)
            throw new IllegalFunctionCodeException("Passed value2 is null but function has paramCount of (" + this.paramCount + ")");

        postCheckExecute(functionData, state, rawFunctionCode);
    }

    /** Actually execute function */
    protected abstract void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException;

}
