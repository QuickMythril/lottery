package org.qortal.at.lottery.jgiven;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import org.ciyam.at.MachineState;
import org.ciyam.at.Timestamp;
import org.ciyam.at.test.ExecutableTest;
import org.ciyam.at.test.TestAPI;

import java.util.List;
import java.util.Random;

// Heavily based on org.ciyam.at.test.ExecutableTest
public class DiceWhen extends Stage<DiceWhen> {
    private static final Random RANDOM = new Random();

    @ExpectedScenarioState
    ExecutableTest test;

    @ExpectedScenarioState(resolution = ScenarioState.Resolution.NAME)
    byte[] creationBytes;

    @ExpectedScenarioState
    Long initialBalance;

    @ExpectedScenarioState
    List<TestAPI.TestAccount> players;

    @ProvidedScenarioState
    Long transactionSearchStartTimestamp;

    public DiceWhen deploy_dice() {
        System.out.println("First execution - deploying...");
        test.state = new MachineState(test.api, test.loggerFactory, creationBytes);
        test.api.setCurrentBalance(initialBalance);
        test.codeBytes = test.state.getCodeBytes();
        test.packedState = test.state.toBytes();

        return self();
    }

    public DiceWhen set_transaction_search_start_block() {
        transactionSearchStartTimestamp = Timestamp.toLong(test.api.blockchain.size(), 0);
        return self();
    }

    public DiceWhen execute_once() {
        test.execute_once();

        return self();
    }

    public DiceWhen execute_until_finished() {
        do {
            execute_once();
        } while (!test.state.isFinished());

        return self();
    }

    public DiceWhen creator_sends_message() {
        // Generate tx hash
        byte[] txHash = new byte[32];
        RANDOM.nextBytes(txHash);

        byte[] message = txHash;
        TestAPI.TestTransaction testTransaction = new TestAPI.TestTransaction(txHash, TestAPI.AT_CREATOR_ADDRESS, TestAPI.AT_ADDRESS, message);
        test.api.addTransactionToCurrentBlock(testTransaction);

        return self();
    }

    @As("player $1 sends message")
    public DiceWhen send_message(int playerIndex) {
        // Generate tx hash
        byte[] txHash = new byte[32];
        RANDOM.nextBytes(txHash);

        TestAPI.TestAccount player = players.get(playerIndex);

        byte[] message = txHash;
        TestAPI.TestTransaction testTransaction = new TestAPI.TestTransaction(txHash, player.address, TestAPI.AT_ADDRESS, message);
        test.api.addTransactionToCurrentBlock(testTransaction);

        return self();
    }

    @As("random player sends payment of $2")
    public DiceWhen send_payment(@QortAmount long amount) {
        // Generate tx hash
        byte[] txHash = new byte[32];
        RANDOM.nextBytes(txHash);

        TestAPI.TestAccount player = players.get(RANDOM.nextInt(players.size()));

        TestAPI.TestTransaction testTransaction = new TestAPI.TestTransaction(txHash, player.address, TestAPI.AT_ADDRESS, amount);
        test.api.addTransactionToCurrentBlock(testTransaction);

        return self();
    }

    @As("player $1 sends payment of $2")
    public DiceWhen send_payment(int playerIndex, @QortAmount long amount) {
        // Generate tx hash
        byte[] txHash = new byte[32];
        RANDOM.nextBytes(txHash);

        TestAPI.TestAccount player = players.get(playerIndex);

        TestAPI.TestTransaction testTransaction = new TestAPI.TestTransaction(txHash, player.address, TestAPI.AT_ADDRESS, amount);
        test.api.addTransactionToCurrentBlock(testTransaction);

        return self();
    }
}
