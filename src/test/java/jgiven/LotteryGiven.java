package jgiven;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import org.ciyam.at.AtLoggerFactory;
import org.ciyam.at.test.QuietTestLoggerFactory;
import org.ciyam.at.test.TestAPI;
import org.ciyam.at.test.TestLoggerFactory;
import org.qortal.at.lottery.Lottery;

import java.util.ArrayList;
import java.util.List;

public class LotteryGiven extends Stage<LotteryGiven> {
    @ProvidedScenarioState
    TestAPI api;

    @ProvidedScenarioState(resolution = ScenarioState.Resolution.NAME)
    byte[] creationBytes;

    @ProvidedScenarioState
    AtLoggerFactory loggerFactory = new TestLoggerFactory();

    @ProvidedScenarioState
    List<TestAPI.TestAccount> players;

    @As("fresh lottery ($1 minute sleep, $2 minimum amount)")
    public LotteryGiven fresh_lottery(int sleepMinutes, @QortAmount long minimumAmount) {
        api = new TestAPI(); // new blockchain
        creationBytes = Lottery.buildQortalAT(sleepMinutes, minimumAmount);

        // Create several potential players
        players = new ArrayList<>();

        for (int i = 0; i < 20; ++i) {
            String address = String.format("Q_player_%02d", i);
            TestAPI.TestAccount player = new TestAPI.TestAccount(address, 100_0000_0000L);
            player.addToMap(api.accounts);
            players.add(player);
        }

        return self();
    }

    public LotteryGiven quiet_logger() {
        loggerFactory = new QuietTestLoggerFactory();

        return self();
    }
}
