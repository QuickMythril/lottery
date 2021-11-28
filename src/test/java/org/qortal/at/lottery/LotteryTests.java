package org.qortal.at.lottery;

import org.qortal.at.lottery.jgiven.AbstractLotteryTest;
import org.junit.Test;

import static org.junit.Assert.assertFalse;

public class LotteryTests extends AbstractLotteryTest {

    private static int DEFAULT_SLEEP_MINUTES = 10;
    private static long DEFAULT_MINIMUM_AMOUNT = 1_0000_0000L; // 1 QORT

    @Test
    public void lottery_should_compile() {
        given()
                .fresh_lottery(DEFAULT_SLEEP_MINUTES, DEFAULT_MINIMUM_AMOUNT);

        then()
                .creation_bytes_exist();
    }

    @Test
    public void lottery_startup() {
        given()
                .fresh_lottery(DEFAULT_SLEEP_MINUTES, DEFAULT_MINIMUM_AMOUNT);

        when()
                .deploy_lottery()
                .execute_once();

        then()
                .AT_is_sleeping();
    }

    @Test
    public void lottery_refunds_creator_if_no_entries() {
        given()
                .fresh_lottery(DEFAULT_SLEEP_MINUTES, DEFAULT_MINIMUM_AMOUNT);

        when()
                .deploy_lottery()
                .execute_until_finished();

        then()
                .AT_is_finished()
                .AT_sent_a_payment()
                .recipient_is_creator();
    }

    @Test
    public void lottery_enforces_minimum_amount() {
        given()
                .fresh_lottery(DEFAULT_SLEEP_MINUTES, DEFAULT_MINIMUM_AMOUNT);

        when()
                .deploy_lottery()
                .execute_once();

        when()
                .send_payment(DEFAULT_MINIMUM_AMOUNT / 2) // too little
                .execute_until_finished();

        then()
                .AT_is_finished()
                .AT_sent_a_payment()
                .recipient_is_creator();
    }

    @Test
    public void lottery_chooses_a_winner() {
        given()
                .fresh_lottery(DEFAULT_SLEEP_MINUTES, DEFAULT_MINIMUM_AMOUNT);

        when()
                .deploy_lottery()
                .execute_once();

        when()
                .send_payment(DEFAULT_MINIMUM_AMOUNT)
                .execute_until_finished();

        then()
                .AT_is_finished()
                .AT_sent_a_payment()
                .recipient_is_not_creator();
    }

    @Test
    public void multiple_entries_have_no_advantage() {
        int[] winsByPlayerIndex = new int[2];

        for (int lotteryCount = 0; lotteryCount < 50; ++lotteryCount) {
            given()
                    .quiet_logger()
                    .fresh_lottery(DEFAULT_SLEEP_MINUTES, DEFAULT_MINIMUM_AMOUNT);

            when()
                    .deploy_lottery()
                    .execute_once();

            // at least one entry by player 0
            when()
                    .send_payment(0, DEFAULT_MINIMUM_AMOUNT);

            // more entries by player 1 than player 0
            for (int entryCount = 0; entryCount < 10; ++entryCount) {
                when()
                        .send_payment(1, DEFAULT_MINIMUM_AMOUNT);
            }

            when()
                    .execute_until_finished();

            then()
                    .AT_is_finished()
                    .AT_sent_a_payment()
                    .recipient_is_not_creator();

            int winnerIndex = then().getWinnerIndex();
            System.out.println(String.format("Lottery won by player %d", winnerIndex));

            winsByPlayerIndex[winnerIndex]++;
        }

        System.err.println(String.format("Lottery wins: player 0 has %d, player 1 has %d", winsByPlayerIndex[0], winsByPlayerIndex[1]));

        // We don't expect player 1 to have way more wins than player 0
        assertFalse(winsByPlayerIndex[1] > 2 * winsByPlayerIndex[0]);

        // We don't expect player 0 to have way more wins than player 1
        assertFalse(winsByPlayerIndex[0] > 2 * winsByPlayerIndex[1]);
    }

}
