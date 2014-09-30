package lighthouse.utils;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableNumberValue;

/** A simple class that reactively formats a string based on an observable amount of satoshis */
public class ReactiveCoinFormatter extends SimpleStringProperty {
    public ReactiveCoinFormatter(String format, MonetaryFormat monetaryFormat, ObservableNumberValue target) {
        calculate(format, monetaryFormat, target);
        target.addListener(obv -> calculate(format, monetaryFormat, target));
    }

    private void calculate(String format, MonetaryFormat monetaryFormat, ObservableNumberValue target) {
        set(String.format(format, monetaryFormat.format(Coin.valueOf(target.longValue()))));
    }
}
