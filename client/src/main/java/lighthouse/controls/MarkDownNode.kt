package lighthouse.controls

import com.google.common.base.CharMatcher
import com.google.common.base.Strings
import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty
import javafx.beans.value.ObservableStringValue
import javafx.geometry.Insets
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.control.ScrollPane
import javafx.scene.control.Separator
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.stage.Stage
import org.pegdown.Extensions
import org.pegdown.PegDownProcessor
import org.pegdown.ast.*
import org.slf4j.LoggerFactory
import java.util.*
import java.util.function.Consumer

public class MarkDownNode : VBox() {
    public var urlOpener: Consumer<String> = Consumer {}
    public val text: StringProperty = SimpleStringProperty()

    public fun setText(new: String) {
        text.set(new)
    }

    init {
        setupCSS()
        text.addListener { obj, old: String?, new: String? ->
            renderMarkDown(preTransform(new ?: ""))
            layout()
        }
    }

    fun preTransform(text: String): String {
        // A few simple regex based transforms to try and handle pre-Markdown formatted projects.
        return text.replace("•", "*").replace("(=+) (.*) =+\n".toRegex()) {
            "#".repeat(it.groups[1]!!.value.length()) + it.groups[2]
        }
    }

    public fun renderMarkDown(text: String) {
        children.clear()
        try {
            val processor = PegDownProcessor(Extensions.SMARTYPANTS or Extensions.AUTOLINKS)
            val rootNode = processor.parseMarkdown(text.toCharArray())
            // printAST(rootNode, 0)
            rootNode.accept(Visitor())
        } catch (t: Throwable) {
            // If anything goes wrong, log it and try to just render plain text instead. Rich text is less important
            // than not crashing.
            log.error("Failed to render Markdown!", t)
            log.error("Failing text was: \n" + text)
            children.setAll(Text(text))
        }
    }

    public fun setupCSS() {
        stylesheets.setAll(javaClass.getResource("markdown.css").toExternalForm())
    }
    
    public interface DefaultVisitor : org.pegdown.ast.Visitor {
        override fun visit(node: AnchorLinkNode) {}
        override fun visit(node: AbbreviationNode) {}
        override fun visit(node: AutoLinkNode) {}
        override fun visit(node: BlockQuoteNode) {}
        override fun visit(node: BulletListNode) {}
        override fun visit(node: CodeNode) {}
        override fun visit(node: DefinitionListNode) {}
        override fun visit(node: DefinitionNode) {}
        override fun visit(node: DefinitionTermNode) {}
        override fun visit(node: ExpImageNode) {}
        override fun visit(node: ExpLinkNode) {}
        override fun visit(node: HeaderNode) {}
        override fun visit(node: HtmlBlockNode) {}
        override fun visit(node: InlineHtmlNode) {}
        override fun visit(node: ListItemNode) {}
        override fun visit(node: MailLinkNode) {}
        override fun visit(node: OrderedListNode) {}
        override fun visit(node: ParaNode) {}
        override fun visit(node: QuotedNode) {}
        override fun visit(node: ReferenceNode) {}
        override fun visit(node: RefImageNode) {}
        override fun visit(node: RefLinkNode) {}
        override fun visit(node: RootNode) {}
        override fun visit(node: SimpleNode) {}
        override fun visit(node: SpecialTextNode) {}
        override fun visit(node: StrikeNode) {}
        override fun visit(node: StrongEmphSuperNode) {}
        override fun visit(node: TableBodyNode) {}
        override fun visit(node: TableCaptionNode) {}
        override fun visit(node: TableCellNode) {}
        override fun visit(node: TableColumnNode) {}
        override fun visit(node: TableHeaderNode) {}
        override fun visit(node: TableNode) {}
        override fun visit(node: TableRowNode) {}
        override fun visit(node: VerbatimNode) {}
        override fun visit(node: WikiLinkNode) {}
        override fun visit(node: TextNode) {}
        override fun visit(node: SuperNode) {}
        override fun visit(node: Node) {}
    }

    private inner class Visitor : DefaultVisitor {
        private var cursor: Pane = this@MarkDownNode
        private val classes = LinkedList<String>()

        public fun descend(node: Node) {
            for (n in node.children) n.accept(this)
        }

        private val NEWLINE_MATCHER = CharMatcher.`is`('\n')

        public fun text(text: String): Text {
            val node = Text(NEWLINE_MATCHER.trimTrailingFrom(text))
            node.styleClass.setAll(classes)
            cursor.children.add(node)
            return node
        }

        inline public fun <reified N : javafx.scene.Node> nodeWithStyles(vararg styles: String): N {
            val node = N::class.java.newInstance()
            node.styleClass.setAll(*styles)
            node.styleClass.addAll(classes)
            return node
        }

        public fun createAndDescend(node: Node, newNode: javafx.scene.layout.Pane, cmd: () -> Unit = {}) {
            val current = cursor
            current.children.add(newNode)
            cursor = newNode
            cmd()
            descend(node)
            cursor = current
        }

        private var nextSuperNodeIsParagraph = false
        override fun visit(node: ParaNode) {
            nextSuperNodeIsParagraph = true
            descend(node)
        }

        override fun visit(node: TextNode) {
            text(node.text)
        }

        override fun visit(node: SuperNode) {
            val textFlow = nodeWithStyles<TextFlow>(
                    if (nextSuperNodeIsParagraph) {
                        nextSuperNodeIsParagraph = false
                        "md-p"
                    } else
                        ""
            )
            textFlow.lineSpacing = 1.2
            createAndDescend(node, textFlow)
        }

        override fun visit(node: AbbreviationNode) = descend(node)

        override fun visit(node: BlockQuoteNode) {
            classes.push("md-blockquote")
            descend(node)
            cursor.children.last().styleClass.remove("md-p")
            classes.pop()
        }

        override fun visit(node: CodeNode) {
            borderedLabel(node, "md-code")
        }

        override fun visit(node: VerbatimNode) {
            val textFlow = nodeWithStyles<TextFlow>("md-verbatim")
            textFlow.children add mdTextToFXText(node)
            val box = VBox(textFlow)
            VBox.setMargin(textFlow, Insets(0.0, 0.0, 15.0, 0.0))
            cursor.children add box
        }

        private fun borderedLabel(node: TextNode, style: String, parent: Pane = cursor): javafx.scene.Node {
            val label = mdTextToFXText(node)
            val wrapper = VBox(label)
            wrapper.styleClass add style
            parent.children add wrapper
            return wrapper
        }

        private fun mdTextToFXText(node: TextNode) = Text(NEWLINE_MATCHER.trimTrailingFrom(node.text))

        override fun visit(node: DefinitionNode) = descend(node)
        override fun visit(node: DefinitionTermNode) = descend(node)

        override fun visit(node: ExpImageNode) {
            cursor.children add ImageView(Image(node.url, true))
        }

        override fun visit(node: RefImageNode) {
            // Unimplemented
        }

        override fun visit(node: HeaderNode) {
            val flow = nodeWithStyles<TextFlow>("md-header", "md-header-${Math.min(5, node.level)}")
            VBox.setMargin(flow, Insets(0.0, 0.0, 15.0, 0.0))
            createAndDescend(node, flow)
        }

        //
        // HTML AND LINKS
        //

        override fun visit(node: AutoLinkNode) = link(node.text, node.text)
        override fun visit(node: ExpLinkNode) {
            val child = node.children[0].children[0]
            if (child is TextNode) {
                val sbLabel = StringBuilder()
                for (child2 in node.children[0].children) {
                    val textNode: TextNode? = child2 as? TextNode
                    if(textNode != null) sbLabel.append(textNode.text)
                }
                link(node.url, sbLabel.toString())
            } else if (child is ExpImageNode) {
                val imageView = ImageView(Image(child.url, true))
                imageView.setOnMouseClicked { this@MarkDownNode.urlOpener.accept(child.url) }
                imageView.cursor = Cursor.HAND
                cursor.children add imageView
            } else {
                log.info("Unknown link child node $child")
            }
        }
        override fun visit(node: MailLinkNode) = link("mailto:" + node.text, node.text)
        override fun visit(node: RefLinkNode) = log.error("Unimplemented: reference links: $node")
        override fun visit(node: AnchorLinkNode) = log.error("Unimplemented: anchor links: $node")

        override fun visit(node: WikiLinkNode) {
            text(node.text)
        }


        private fun link(url: String, text: String) {
            val link = text(text)
            link.fill = Color.BLUE
            link.styleClass.add("md-link")
            link.setOnMouseClicked { this@MarkDownNode.urlOpener.accept(url) }
        }

        // Just pass straight through
        override fun visit(node: HtmlBlockNode) = descend(node)
        override fun visit(node: InlineHtmlNode) = descend(node)

        //
        // LISTS
        //

        private var listCounter = linkedListOf<Int>();  // 0 == bullet, >0 == number
        override fun visit(node: BulletListNode) {
            createAndDescend(node, nodeWithStyles<VBox>("md-p")) {
                listCounter push 0
            }
            listCounter.pop()
        }

        override fun visit(node: OrderedListNode) {
            createAndDescend(node, nodeWithStyles<VBox>("md-p")) {
                listCounter push 1
            }
            listCounter.pop()
        }

        override fun visit(node: ListItemNode) {
            createAndDescend(node, nodeWithStyles<HBox>("md-li")) {
                cursor.children add Text(nextBullet())
                val box = VBox()
                cursor.children add box
                cursor = box
            }
        }

        private fun nextBullet(): String {
            val l = listCounter.peek()
            return if (l == 0)
                "•  "
            else if (l > 0) {
                listCounter.pop()
                listCounter.push(l + 1)
                "$l. "
            } else
                throw IllegalStateException()
        }

        // Ignore
        override fun visit(node: DefinitionListNode) = node.accept(this)
        override fun visit(node: QuotedNode) {
            when (node.type!!) {
                QuotedNode.Type.DoubleAngle -> {
                    text("«")
                    descend(node)
                    text("»")
                }
                QuotedNode.Type.Double -> {
                    text("“")
                    descend(node)
                    text("”")
                }
                QuotedNode.Type.Single -> {
                    text("‘")
                    descend(node)
                    text("’")
                }
            }
        }
        override fun visit(node: ReferenceNode) {}
        override fun visit(node: RootNode) = descend(node)

        override fun visit(node: SimpleNode) {
            when (node.type!!) {
                SimpleNode.Type.Apostrophe -> text("\u2019")
                SimpleNode.Type.Ellipsis -> text("\u2026")
                SimpleNode.Type.Emdash -> text("\u2014")
                SimpleNode.Type.Endash -> text("\u2013")
                SimpleNode.Type.Nbsp -> text("\u00a0")


                SimpleNode.Type.Linebreak -> {
                }
                SimpleNode.Type.HRule -> {
                    cursor.children add Separator()
                }
            }
        }

        override fun visit(node: SpecialTextNode) {
            text(node.text)
        }

        override fun visit(node: StrikeNode) = descend(node)

        override fun visit(node: StrongEmphSuperNode) {
            classes.push("md-emph")
            descend(node)
            classes.pop()
        }
    }

    companion object {
        val log = LoggerFactory.getLogger(MarkDownNode::class.java)

        @JvmStatic public fun printAST(node: Node, offset: Int) {
            System.out.print(Strings.repeat(" ", offset))
            System.out.println(node)
            for (n in node.children) {
                printAST(n, offset + 2)
            }
        }

        @JvmStatic public fun countFormattingNodes(ast: Node): Int = (if (isTextNode(ast)) 0 else 1) + ast.children.map { countFormattingNodes(it) }.sum()

        private fun isTextNode(ast: Node) = ast is ParaNode || ast is TextNode || ast is RootNode || ast.javaClass == SuperNode::class.java

        @JvmStatic public fun openPopup(text: ObservableStringValue, urlOpener: Consumer<String>) {
            val node = MarkDownNode()
            node.text.bind(text)
            node.urlOpener = urlOpener
            // For some reason there are graphics glitches if padding is applied to the scrollpane directly. Wrapping
            // in another node makes the rendering errors go away.
            val wrapper = StackPane(node)
            wrapper.padding = Insets(15.0)
            val scrollPane = ScrollPane(wrapper)
            scrollPane.prefWidth = 600.0
            scrollPane.prefHeight = 400.0
            scrollPane.isFitToWidth = true
            val scene = Scene(scrollPane)
            val stage = Stage()
            stage.scene = scene
            stage.title = "Preview"
            stage.isAlwaysOnTop = true
            stage.show()
        }
    }
}
