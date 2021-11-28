package org.qortal.at.lottery.jgiven;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import org.ciyam.at.MachineState;
import org.ciyam.at.test.ExecutableTest;
import org.ciyam.at.test.TestAPI;

import java.util.List;
import java.util.Random;

// Heavily based on org.ciyam.at.test.ExecutableTest
public class LotteryWhen extends Stage<LotteryWhen> {
    private static final Random RANDOM = new Random();

    @ExpectedScenarioState
    ExecutableTest test;

    @ExpectedScenarioState(resolution = ScenarioState.Resolution.NAME)
    byte[] creationBytes;

    @ExpectedScenarioState
    List<TestAPI.TestAccount> players;

    public LotteryWhen deploy_lottery() {
        System.out.println("First execution - deploying...");
        test.state = new MachineState(test.api, test.loggerFactory, creationBytes);
        test.codeBytes = test.state.getCodeBytes();
        test.packedState = test.state.toBytes();

        return self();
    }

    public LotteryWhen execute_once() {
        test.execute_once();

        return self();
    }

    public LotteryWhen execute_until_finished() {
        do {
            test.execute_once();
        } while (!test.state.isFinished());

        return self();
    }

    @As("random player sends payment of $2")
    public LotteryWhen send_payment(@QortAmount long amount) {
        // Generate tx hash
        byte[] txHash = new byte[32];
        RANDOM.nextBytes(txHash);

        TestAPI.TestAccount player = players.get(RANDOM.nextInt(players.size()));

        TestAPI.TestTransaction testTransaction = new TestAPI.TestTransaction(txHash, player.address, TestAPI.AT_ADDRESS, amount);
        test.api.addTransactionToCurrentBlock(testTransaction);

        return self();
    }

    @As("player $1 sends payment of $2")
    public LotteryWhen send_payment(int playerIndex, @QortAmount long amount) {
        // Generate tx hash
        byte[] txHash = new byte[32];
        RANDOM.nextBytes(txHash);

        TestAPI.TestAccount player = players.get(playerIndex);

        TestAPI.TestTransaction testTransaction = new TestAPI.TestTransaction(txHash, player.address, TestAPI.AT_ADDRESS, amount);
        test.api.addTransactionToCurrentBlock(testTransaction);

        return self();
    }
}
