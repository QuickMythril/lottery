package org.qortal.at.lottery.jgiven;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import org.ciyam.at.API;
import org.ciyam.at.test.ExecutableTest;
import org.ciyam.at.test.TestAPI;

import java.util.List;

import static org.junit.Assert.*;

public class DiceThen extends Stage<DiceThen> {
    @ExpectedScenarioState
    ExecutableTest test;

    @ExpectedScenarioState(resolution = ScenarioState.Resolution.NAME)
    byte[] creationBytes;

    @ExpectedScenarioState
    List<TestAPI.TestAccount> players;

    @ExpectedScenarioState
    Long transactionSearchStartTimestamp;

    @ProvidedScenarioState
    TestAPI.TestTransaction testTransaction;

    public DiceThen creation_bytes_exist() {
        assertNotNull(creationBytes);
        assertTrue(creationBytes.length > 0);
        return self();
    }

    public DiceThen AT_is_sleeping() {
        assertTrue(test.state.isSleeping());
        return self();
    }

    public DiceThen AT_is_finished() {
        assertTrue(test.state.isFinished());
        return self();
    }

    public DiceThen AT_sent_no_payment() {
        maybe_find_AT_payment();

        assertNull(testTransaction);

        return self();
    }

    public DiceThen AT_sent_a_payment() {
        maybe_find_AT_payment();

        assertNotNull(testTransaction);

        return self();
    }

    public DiceThen maybe_find_AT_payment() {
        testTransaction = null;

        // Latest first
        for (TestAPI.TestTransaction transaction : test.api.atTransactions) {
            if (transaction.txType.equals(API.ATTransactionType.PAYMENT) &&
                    transaction.sender.equals(TestAPI.AT_ADDRESS) &&
                    (transactionSearchStartTimestamp == null || transaction.timestamp >= transactionSearchStartTimestamp)
            ) {
                // Save transaction for other tests
                testTransaction = transaction;
                break;
            }
        }

        return self();
    }

    public DiceThen recipient_is_creator() {
        assertNotNull(testTransaction);
        assertEquals(TestAPI.AT_CREATOR_ADDRESS, testTransaction.recipient);
        return self();
    }

    public DiceThen recipient_is_not_creator() {
        assertNotNull(testTransaction);
        assertNotEquals(TestAPI.AT_CREATOR_ADDRESS, testTransaction.recipient);
        return self();
    }

    public DiceThen recipient_is_$(String recipient) {
        assertNotNull(testTransaction);
        assertEquals(recipient, testTransaction.recipient);
        return self();
    }

    public Integer getWinnerIndex() {
        if (testTransaction == null)
            return null;

        for (int winnerIndex = 0; winnerIndex < players.size(); ++winnerIndex)
            if (players.get(winnerIndex).address.equals(testTransaction.recipient))
                return winnerIndex;

        fail(String.format("AT payment recipient is not a player: %s", testTransaction.recipient));
        return -1; // not reached
    }
}
