import jgiven.AbstractLotteryTest;
import org.junit.Test;

import static org.junit.Assert.assertFalse;

public class LotteryTests extends AbstractLotteryTest {

    @Test
    public void lottery_should_compile() {
        given()
                .fresh_lottery(60, 1_0000_0000L);

        then()
                .creation_bytes_exist();
    }

    @Test
    public void lottery_startup() {
        given()
                .fresh_lottery(10, 1_0000_0000L);

        when()
                .deploy_lottery()
                .execute_once();

        then()
                .AT_is_sleeping();
    }

    @Test
    public void lottery_refunds_creator_if_no_entries() {
        given()
                .fresh_lottery(10, 1_0000_0000L);

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
        long minimumAmount = 1_0000_0000L;

        given()
                .fresh_lottery(10, minimumAmount);

        when()
                .deploy_lottery()
                .execute_once();

        when()
                .send_payment(minimumAmount / 2) // too little
                .execute_until_finished();

        then()
                .AT_is_finished()
                .AT_sent_a_payment()
                .recipient_is_creator();
    }

    @Test
    public void lottery_chooses_a_winner() {
        long minimumAmount = 1_0000_0000L;

        given()
                .fresh_lottery(10, minimumAmount);

        when()
                .deploy_lottery()
                .execute_once();

        when()
                .send_payment(minimumAmount)
                .execute_until_finished();

        then()
                .AT_is_finished()
                .AT_sent_a_payment()
                .recipient_is_not_creator();
    }

    @Test
    public void multiple_entries_have_no_advantage() {
        long minimumAmount = 1_0000_0000L;

        int[] winsByPlayerIndex = new int[2];

        for (int lotteryCount = 0; lotteryCount < 50; ++lotteryCount) {
            given()
                    .fresh_lottery(10, minimumAmount)
                    .quiet_logger();

            when()
                    .deploy_lottery()
                    .execute_once();

            // at least one entry by player 0
            when()
                    .send_payment(0, minimumAmount);

            // more entries by player 1 than player 0
            for (int entryCount = 0; entryCount < 10; ++entryCount) {
                when()
                        .send_payment(1, minimumAmount);
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
