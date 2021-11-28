package org.qortal.at.lottery.jgiven;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import org.ciyam.at.AtLoggerFactory;
import org.ciyam.at.test.ExecutableTest;
import org.ciyam.at.test.QuietTestLoggerFactory;
import org.ciyam.at.test.TestAPI;
import org.ciyam.at.test.TestLoggerFactory;
import org.qortal.at.lottery.Dice;
import org.qortal.at.lottery.DiceAPI;

import java.util.ArrayList;
import java.util.List;

public class DiceGiven extends Stage<DiceGiven> {
    @ProvidedScenarioState
    ExecutableTest test;

    @ProvidedScenarioState(resolution = ScenarioState.Resolution.NAME)
    byte[] creationBytes;

    @ProvidedScenarioState
    Long initialBalance;

    @ProvidedScenarioState
    AtLoggerFactory loggerFactory = new TestLoggerFactory();

    @ProvidedScenarioState
    List<TestAPI.TestAccount> players;

    @As("fresh dice ($1 minimum amount, $2 initial balance)")
    public DiceGiven fresh_dice(@QortAmount long minimumAmount, @QortAmount long initialBalance) {
        test = new ExecutableTest();
        test.loggerFactory = loggerFactory;
        test.api = new DiceAPI(); // new blockchain

        creationBytes = Dice.buildQortalAT(minimumAmount);

        this.initialBalance = initialBalance;

        // Create several potential players
        players = new ArrayList<>();

        for (int i = 0; i < 20; ++i) {
            String address = String.format("Q_player_%02d", i);
            TestAPI.TestAccount player = new TestAPI.TestAccount(address, 100_0000_0000L);
            player.addToMap(test.api.accounts);
            players.add(player);
        }

        return self();
    }

    public DiceGiven quiet_logger() {
        loggerFactory = new QuietTestLoggerFactory();

        return self();
    }
}
