package lighthouse.controls

import javafx.animation.Interpolator
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.beans.value.WritableValue
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.shape.Rectangle
import javafx.util.Duration
import lighthouse.utils.I18nUtil.tr
import lighthouse.utils.easing.ElasticInterpolator

/**
 * Control that wraps another control and hides most of it until the Read more ... link is clicked, when it animates
 * itself into the right size.
 */
@Suppress("UNCHECKED_CAST")    // Hack around Kotlin compiler bug
public class ReadMorePane() : StackPane() {
    init {
        minHeight = 65.0
        children.addListener(object : InvalidationListener {
            override fun invalidated(observable: Observable?) {
                children.removeListener(this)

                // Wait for a layout pass to have happened so we can query the wrapped height.
                // This is ugly! We should really figure out how to do this without waiting.
                Platform.runLater {
                    val wrappedNode = children[0] as Region

                    if (wrappedNode.height < prefHeight) {
                        prefHeight = wrappedNode.height
                    } else {
                        wrappedNode.minHeight = 0.0
                        val label = Label(tr("Read more ..."))
                        label.style = "-fx-text-fill: blue; -fx-cursor: hand"
                        val box = HBox(label)
                        box.alignment = Pos.BOTTOM_RIGHT
                        box.style = "-fx-padding: 5px; -fx-background-color: linear-gradient(from 0% 0% to 0% 50%, #ffffff00, white)"
                        box.maxHeight = 100.0
                        StackPane.setAlignment(box, Pos.BOTTOM_RIGHT)
                        children.add(box)

                        val clipRect = Rectangle()
                        clipRect.widthProperty()  bind widthProperty()
                        clipRect.heightProperty() bind heightProperty()
                        wrappedNode.clip = clipRect

                        label.setOnMouseClicked {
                            it.consume()

                            // We must remove the clip to find this out. We cannot query it above because at that point we're not
                            // a part of the scene and have not done layout. Plus images may be loading async, etc.
                            wrappedNode.clip = null
                            val realHeight = wrappedNode.boundsInLocal.height
                            wrappedNode.clip = clipRect

                            val readMoreClip = Rectangle()
                            readMoreClip.widthProperty() bind widthProperty()
                            readMoreClip.height = box.height

                            clipRect.heightProperty().unbind()
                            Timeline(
                                    KeyFrame(
                                            Duration.seconds(0.15),
                                            KeyValue(box.translateXProperty() as WritableValue<Any?>, 5.0, Interpolator.EASE_OUT),
                                            KeyValue(box.opacityProperty() as WritableValue<Any?>, 0.0)
                                    )
                            ).play()
                            val interp = ElasticInterpolator()
                            val timeline = Timeline(
                                    KeyFrame(
                                            Duration.seconds(1.5),
                                            KeyValue(minHeightProperty() as WritableValue<Any?>, realHeight, interp),
                                            KeyValue(maxHeightProperty() as WritableValue<Any?>, realHeight, interp),
                                            KeyValue(clipRect.heightProperty() as WritableValue<Any?>, realHeight, interp),
                                            KeyValue(box.minHeightProperty() as WritableValue<Any?>, 0),
                                            KeyValue(box.maxHeightProperty() as WritableValue<Any?>, 0)
                                    )
                            )
                            timeline.delay = Duration.seconds(0.1)
                            timeline.setOnFinished {
                                children.remove(box)
                                wrappedNode.clip = null
                            }
                            timeline.play()
                        }
                    }
                }
            }
        })
    }
}