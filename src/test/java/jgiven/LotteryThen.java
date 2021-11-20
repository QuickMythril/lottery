package jgiven;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import org.ciyam.at.API;
import org.ciyam.at.MachineState;
import org.ciyam.at.test.TestAPI;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class LotteryThen extends Stage<LotteryThen> {
    @ExpectedScenarioState(resolution = ScenarioState.Resolution.NAME)
    byte[] creationBytes;

    @ExpectedScenarioState
    TestAPI api;

    @ExpectedScenarioState
    MachineState state;

    @ExpectedScenarioState
    List<TestAPI.TestAccount> players;

    @ProvidedScenarioState
    TestAPI.TestTransaction testTransaction;

    public LotteryThen creation_bytes_exist() {
        assertNotNull(creationBytes);
        assertTrue(creationBytes.length > 0);
        return self();
    }

    public LotteryThen AT_is_sleeping() {
        assertTrue(state.isSleeping());
        return self();
    }

    public LotteryThen AT_is_finished() {
        assertTrue(state.isFinished());
        return self();
    }

    public LotteryThen AT_sent_a_payment() {
        // Find AT PAYMENT
        Optional<TestAPI.TestTransaction> maybeTransaction = api.atTransactions.stream()
                .filter(transaction ->
                        transaction.txType.equals(API.ATTransactionType.PAYMENT) &&
                                transaction.sender.equals(TestAPI.AT_ADDRESS))
                .findFirst();

        assertTrue(maybeTransaction.isPresent());

        // Save transaction for other tests
        testTransaction = maybeTransaction.get();

        return self();
    }

    public LotteryThen recipient_is_creator() {
        assertNotNull(testTransaction);
        assertEquals(TestAPI.AT_CREATOR_ADDRESS, testTransaction.recipient);
        return self();
    }

    public LotteryThen recipient_is_not_creator() {
        assertNotNull(testTransaction);
        assertNotEquals(TestAPI.AT_CREATOR_ADDRESS, testTransaction.recipient);
        return self();
    }

    public LotteryThen recipient_is_$(String recipient) {
        assertNotNull(testTransaction);
        assertEquals(recipient, testTransaction.recipient);
        return self();
    }

    public int getWinnerIndex() {
        assertNotNull(testTransaction);

        for (int winnerIndex = 0; winnerIndex < players.size(); ++winnerIndex)
            if (players.get(winnerIndex).address.equals(testTransaction.recipient))
                return winnerIndex;

        fail(String.format("AT payment recipient is not a player: %s", testTransaction.recipient));
        return -1; // not reached
    }
}
