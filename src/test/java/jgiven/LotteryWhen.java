package jgiven;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import org.ciyam.at.AtLoggerFactory;
import org.ciyam.at.MachineState;
import org.ciyam.at.test.TestAPI;

import java.util.List;
import java.util.Random;

// Heavily based on org.ciyam.at.test.ExecutableTest
public class LotteryWhen extends Stage<LotteryWhen> {
    private static final Random RANDOM = new Random();

    @ExpectedScenarioState(resolution = ScenarioState.Resolution.NAME)
    byte[] creationBytes;

    @ExpectedScenarioState
    AtLoggerFactory loggerFactory;

    @ExpectedScenarioState
    TestAPI api;

    @ExpectedScenarioState
    List<TestAPI.TestAccount> players;

    @ProvidedScenarioState
    MachineState state;

    @ProvidedScenarioState(resolution = ScenarioState.Resolution.NAME)
    byte[] codeBytes;

    @ProvidedScenarioState(resolution = ScenarioState.Resolution.NAME)
    byte[] packedState;

    public LotteryWhen deploy_lottery() {
        System.out.println("First execution - deploying...");
        state = new MachineState(api, loggerFactory, creationBytes);
        codeBytes = state.getCodeBytes();
        packedState = state.toBytes();

        return self();
    }

    public LotteryWhen execute_once() {
        state = MachineState.fromBytes(api, loggerFactory, packedState, codeBytes);

        System.out.println("Starting execution round!");
        System.out.println("Current block height: " + api.getCurrentBlockHeight());
        System.out.println("Previous balance: " + TestAPI.prettyAmount(state.getPreviousBalance()));
        System.out.println("Current balance: " + TestAPI.prettyAmount(state.getCurrentBalance()));

        // Actual execution
        state.execute();

        System.out.println("After execution round:");
        System.out.println("Steps: " + state.getSteps());
        System.out.println(String.format("Program Counter: 0x%04x", state.getProgramCounter()));
        System.out.println(String.format("Stop Address: 0x%04x", state.getOnStopAddress()));
        System.out.println("Error Address: " + (state.getOnErrorAddress() == null ? "not set" : String.format("0x%04x", state.getOnErrorAddress())));

        if (state.isSleeping())
            System.out.println("Sleeping until current block height (" + state.getCurrentBlockHeight() + ") reaches " + state.getSleepUntilHeight());
        else
            System.out.println("Sleeping: " + state.isSleeping());

        System.out.println("Stopped: " + state.isStopped());
        System.out.println("Finished: " + state.isFinished());

        if (state.hadFatalError())
            System.out.println("Finished due to fatal error!");

        System.out.println("Frozen: " + state.isFrozen());

        long newBalance = state.getCurrentBalance();
        System.out.println("New balance: " + TestAPI.prettyAmount(newBalance));
        api.setCurrentBalance(newBalance);

        // Add block, possibly containing AT-created transactions, to chain to at least provide block hashes
        api.addCurrentBlockToChain();

        // Bump block height
        api.bumpCurrentBlockHeight();

        packedState = state.toBytes();
        System.out.println("Execution round finished\n");

        return self();
    }

    public LotteryWhen execute_until_finished() {
        do {
            execute_once();
        } while (!state.isFinished());

        return self();
    }

    @As("random player sends payment of $2")
    public LotteryWhen send_payment(@QortAmount long amount) {
        // Generate tx hash
        byte[] txHash = new byte[32];
        RANDOM.nextBytes(txHash);

        TestAPI.TestAccount player = players.get(RANDOM.nextInt(players.size()));

        TestAPI.TestTransaction testTransaction = new TestAPI.TestTransaction(txHash, player.address, TestAPI.AT_ADDRESS, amount);
        api.addTransactionToCurrentBlock(testTransaction);

        return self();
    }

    @As("player $1 sends payment of $2")
    public LotteryWhen send_payment(int playerIndex, @QortAmount long amount) {
        // Generate tx hash
        byte[] txHash = new byte[32];
        RANDOM.nextBytes(txHash);

        TestAPI.TestAccount player = players.get(playerIndex);

        TestAPI.TestTransaction testTransaction = new TestAPI.TestTransaction(txHash, player.address, TestAPI.AT_ADDRESS, amount);
        api.addTransactionToCurrentBlock(testTransaction);

        return self();
    }
}
