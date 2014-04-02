package lighthouse.utils;

import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.function.Predicate;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

public class ValidationLink {
    public final TextField field;
    public final Predicate<String> predicate;
    public final BooleanProperty isValid;

    public ValidationLink(TextField field, Predicate<String> predicate) {
        this.field = field;
        this.predicate = predicate;
        isValid = new TextFieldValidator(field, predicate).valid;
    }

    public static void autoDisableButton(Button button, ValidationLink... links) {
        List<BooleanProperty> props = stream(links).map(ValidationLink::isValidProperty).collect(toList());
        BooleanBinding allValid = GuiUtils.conjunction(props);
        button.disableProperty().bind(allValid.not());
    }

    public BooleanProperty isValidProperty() {
        return isValid;
    }
}
