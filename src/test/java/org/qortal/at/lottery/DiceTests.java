package org.qortal.at.lottery;

import com.tngtech.jgiven.annotation.As;
import org.junit.Test;
import org.qortal.at.lottery.jgiven.AbstractDiceTest;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

public class DiceTests extends AbstractDiceTest {

    private static long DEFAULT_MINIMUM_AMOUNT = 1_0000_0000L; // 1 QORT
    private static long DEFAULT_INITIAL_BALANCE = 10_0000_0000L; // 10 QORT

    private static Random RANDOM = new Random();

    @Test
    public void dice_should_compile() {
        given()
                .fresh_dice(DEFAULT_MINIMUM_AMOUNT, DEFAULT_INITIAL_BALANCE);

        then()
                .creation_bytes_exist();
    }

    @Test
    public void dice_startup() {
        given()
                .fresh_dice(DEFAULT_MINIMUM_AMOUNT, DEFAULT_INITIAL_BALANCE);

        when()
                .deploy_dice()
                .execute_once();

        then()
                .AT_is_sleeping();
    }

    @Test
    public void dice_refunds_creator() {
        given()
                .fresh_dice(DEFAULT_MINIMUM_AMOUNT, DEFAULT_INITIAL_BALANCE);

        when()
                .deploy_dice()
                .execute_once();

        when()
                .creator_sends_message()
                .execute_once(); // AT won't run but block contains MESSAGE from creator to AT

        when()
                .execute_once(); // AT should run

        then()
                .AT_is_finished()
                .AT_sent_a_payment()
                .recipient_is_creator();
    }

    @Test
    @As("dice doesn't refund non-creator")
    public void dice_doesnt_refund_non_creator() {
        given()
                .fresh_dice(DEFAULT_MINIMUM_AMOUNT, DEFAULT_INITIAL_BALANCE);

        when()
                .deploy_dice()
                .send_message(0)
                .execute_once(); // AT won't run but block contains MESSAGE from non-creator to AT

        when()
                .execute_once(); // AT should run

        then()
                .AT_is_sleeping()
                .AT_sent_no_payment();
    }

    @Test
    public void dice_enforces_minimum_amount() {
        given()
                .fresh_dice(DEFAULT_MINIMUM_AMOUNT, DEFAULT_INITIAL_BALANCE);

        when()
                .deploy_dice()
                .execute_once();

        when()
                .send_payment(DEFAULT_MINIMUM_AMOUNT / 2) // too little
                .execute_once(); // AT won't run but block contains PAYMENT

        when()
                .execute_once(); // AT should run

        then()
                .AT_is_sleeping()
                .AT_sent_no_payment();
    }

    @Test
    public void dice_enforces_maximum_amount() {
        given()
                .fresh_dice(DEFAULT_MINIMUM_AMOUNT, DEFAULT_INITIAL_BALANCE);

        when()
                .deploy_dice()
                .execute_once();

        when()
                .send_payment(0, DEFAULT_INITIAL_BALANCE * 2) // too much
                .execute_once(); // AT won't run but block contains PAYMENT

        when()
                .execute_once(); // AT should run

        then()
                .AT_is_sleeping()
                .AT_sent_a_payment() // refund
                .recipient_is_not_creator();
    }

    @Test
    public void single_play() {
        int playerIndex = 0;

        given()
                .fresh_dice(DEFAULT_MINIMUM_AMOUNT, DEFAULT_INITIAL_BALANCE * 8L);

        when()
                .deploy_dice()
                .execute_once();

        when()
                .send_payment(playerIndex, DEFAULT_MINIMUM_AMOUNT)
                .execute_once(); // AT won't run but block contains PAYMENT

        when()
                .set_transaction_search_start_block()
                .execute_once(); // AT should run

        Integer winnerIndex = then()
                .maybe_find_AT_payment()
                .getWinnerIndex();

        if (winnerIndex != null) {
            System.err.println(String.format("Dice round won by player %d", winnerIndex));
            assertEquals(playerIndex, winnerIndex.intValue());
        }
    }

    @Test
    public void multiple_plays() {
        int[] winsByPlayerIndex = new int[2];
        int targetPlayCount = 600;

        given()
                .quiet_logger()
                .fresh_dice(DEFAULT_MINIMUM_AMOUNT, DEFAULT_INITIAL_BALANCE * targetPlayCount * 6L);

        when()
                .deploy_dice()
                .execute_once();

        for (int playCount = 0; playCount < targetPlayCount; ++playCount) {
            int playerIndex = RANDOM.nextInt(winsByPlayerIndex.length);

            when()
                    .send_payment(playerIndex, DEFAULT_MINIMUM_AMOUNT)
                    .execute_once(); // AT won't run but block contains PAYMENT

            when()
                    .set_transaction_search_start_block()
                    .execute_once(); // AT should run

            Integer winnerIndex = then()
                    .maybe_find_AT_payment()
                    .getWinnerIndex();

            if (winnerIndex != null) {
                System.err.println(String.format("Dice round %d won by player %d", playCount, winnerIndex));
                assertEquals(playerIndex, winnerIndex.intValue());
                winsByPlayerIndex[winnerIndex]++;
            }
        }

        int totalWins = Arrays.stream(winsByPlayerIndex).sum();
        // We expect totalWins to be around 1/6th of total plays
        int playsPerWinPct = (totalWins * 100) / targetPlayCount;

        System.err.println(String.format("Out of %d rounds, player 0 won %d, player 1 won %d, total wins: %d ~%d%%",
                targetPlayCount,
                winsByPlayerIndex[0],
                winsByPlayerIndex[1],
                totalWins,
                playsPerWinPct));

        // We don't expect player 1 to have way more wins than player 0
        assertFalse(winsByPlayerIndex[1] > 2 * winsByPlayerIndex[0]);

        // We don't expect player 0 to have way more wins than player 1
        assertFalse(winsByPlayerIndex[0] > 2 * winsByPlayerIndex[1]);

        // 16.6% but allow 5% either way?
        assertTrue(playsPerWinPct > 11 && playsPerWinPct < 21);
    }

}
