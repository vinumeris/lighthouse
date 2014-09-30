package lighthouse.utils;

import org.junit.Test;

import static lighthouse.utils.BitcoinValue.userInputToSatoshis;
import static org.junit.Assert.assertEquals;

public class BitcoinValueTest {
    @Test
    public void userInputOK() throws Exception {
        assertEquals(100_000_000, userInputToSatoshis("1.0"));
        assertEquals(0, userInputToSatoshis("0.0"));
        assertEquals(0, userInputToSatoshis("0"));
        assertEquals(1, userInputToSatoshis("0.00000001"));
        assertEquals(21_000_000L * 100_000_000L, userInputToSatoshis("21000000"));
    }

    @Test(expected = NumberFormatException.class)
    public void tooSmall() throws Exception {
        userInputToSatoshis("0.000000001");
    }

    @Test(expected = NumberFormatException.class)
    public void tooLarge() throws Exception {
        userInputToSatoshis("21000000.1");
    }

    @Test(expected = NumberFormatException.class)
    public void tooNotNumeric() throws Exception {
        userInputToSatoshis("1.0 mBTC");   // TODO: Maybe make this valid later.
    }
}
