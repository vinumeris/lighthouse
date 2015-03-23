package lighthouse.utils;

import org.bitcoinj.core.NetworkParameters;

import java.math.BigDecimal;

import static lighthouse.utils.I18nUtil._;

// TODO: Move this into bitcoinj

public class BitcoinValue {
    // Takes user input in bitcoins and yields either a value in satoshis or a NumberFormatException if out of range.
    public static long userInputToSatoshis(String userInput) throws NumberFormatException {
        BigDecimal bd = new BigDecimal(userInput.trim());
        if (bd.signum() < 0)
            throw new NumberFormatException(_("Negative numbers not allowed"));
        final BigDecimal satoshis = bd.movePointRight(8);
        if (satoshis.scale() > 0)
            throw new NumberFormatException(_("Fractional value units not allowed"));
        final long l = satoshis.longValue();
        if (l > NetworkParameters.MAX_MONEY.longValue())
            throw new NumberFormatException(_("Larger than MAX_MONEY"));
        return l;
    }
}
