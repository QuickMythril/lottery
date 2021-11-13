import org.junit.Test;
import org.qortal.at.lottery.Lottery;

public class LotteryTests {

    @Test
    public void testCompile() {
        byte[] creationBytes = Lottery.buildQortalAT(60, 1_0000_0000L);
    }
}
