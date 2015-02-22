package lighthouse.controls;

import javafx.scene.*;
import javafx.scene.paint.*;
import javafx.scene.text.*;
import javafx.util.*;
import lighthouse.*;

import java.util.*;
import java.util.regex.*;

/**
 * A TextFlow extension that auto-linkifies text.
 */
public class DescriptionFlow extends TextFlow {
    private static final Pattern links = Pattern.compile("http[s]://[^ \\n()]+");
    private static final int READ_MORE_THRESHOLD = 1000;   // in characters.

    public void setText(String text) {
        List<Text> nodes = linkifyText(text);
        if (text.length() > READ_MORE_THRESHOLD) {
            int sentenceEdge = text.substring(0, READ_MORE_THRESHOLD).lastIndexOf(".");
            Pair<Integer, Integer> pair = findNodeForCharIndex(sentenceEdge, nodes);
            int nodeIndex = pair.getKey();
            int offset = pair.getValue();
            splitNode(nodes, nodeIndex, offset);
            List<Text> readMoreNodes = nodes.subList(nodeIndex + 1, nodes.size());
            getChildren().setAll(nodes.subList(0, nodeIndex + 1));
            getChildren().addAll(new Text("    "), createReadMoreLink(readMoreNodes));
        } else {
            getChildren().setAll(nodes);
        }
    }

    private Text createReadMoreLink(List<Text> nodes) {
        Text link = new Text("Read more ...");
        link.setFill(Color.BLUE);
        link.setOnMouseClicked((ev) -> {
            getChildren().remove(getChildren().size() - 2, getChildren().size() );
            getChildren().addAll(nodes);
            ev.consume();  // Don't allow the event to bubble up and trigger a switch to project view.
        });
        return link;
    }

    public void splitNode(List<Text> nodes, int nodeIndex, int offset) {
        Text first = nodes.get(nodeIndex);
        String orig = first.getText();
        first = new Text(orig.substring(0, offset));
        Text second = new Text(orig.substring(offset));
        nodes.set(nodeIndex, first);
        nodes.add(nodeIndex + 1, second);
    }

    private Pair<Integer, Integer> findNodeForCharIndex(int edge, List<Text> nodes) {
        int charCursor = 0;
        int nodeCursor = 0;
        while (charCursor < edge) {
            Text node = nodes.get(nodeCursor);
            int l = node.getText().length();
            if (l + charCursor >= edge)
                return new Pair<>(nodeCursor, node.getText().length() - (l + charCursor - edge) + 1);
            nodeCursor++;
            charCursor += l;
        }
        throw new IllegalArgumentException();
    }


    public List<Text> linkifyText(String text) {
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
        return nodes;
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
