package lighthouse.controls;

import javafx.scene.*;
import javafx.scene.paint.*;
import javafx.scene.text.*;
import lighthouse.*;

import java.util.*;
import java.util.regex.*;

/**
 * A TextFlow extension that auto-linkifies text.
 */
public class DescriptionFlow extends TextFlow {
    private static final Pattern links = Pattern.compile("http[s]://[^ \\n()]+");

    public void setText(String text) {
        Matcher matcher = links.matcher(text);
        List<Text> nodes = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            String prev = text.substring(cursor, matcher.start());
            nodes.add(new Text(prev));
            String url = text.substring(matcher.start(), matcher.end());
            boolean ignoredLastDot = url.endsWith(".");
            if (ignoredLastDot)
                url = url.substring(0, url.length() - 1);
            nodes.add(makeClickableLink(url));
            cursor = matcher.end() + (ignoredLastDot ? 1 : 0);
        }
        if (cursor != text.length())
            nodes.add(new Text(text.substring(cursor)));
        getChildren().setAll(nodes);
    }

    public Text makeClickableLink(String url) {
        Text text = new Text(url);
        text.setUnderline(true);
        text.setFill(Color.BLUE);
        text.setCursor(Cursor.HAND);
        text.setOnMouseClicked((ev) -> Main.instance.getHostServices().showDocument(url));
        return text;
    }
}
