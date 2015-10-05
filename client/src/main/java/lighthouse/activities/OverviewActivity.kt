package lighthouse.activities

import de.jensd.fx.fontawesome.AwesomeDude
import de.jensd.fx.fontawesome.AwesomeIcon
import javafx.animation.*
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.property.SimpleLongProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.WritableValue
import javafx.collections.ListChangeListener
import javafx.collections.MapChangeListener
import javafx.collections.ObservableList
import javafx.collections.ObservableMap
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.control.Label
import javafx.scene.input.DragEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.TransferMode
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.util.Duration
import lighthouse.LighthouseBackend
import lighthouse.Main
import lighthouse.MainWindow
import lighthouse.controls.ProjectOverviewWidget
import lighthouse.files.AppDirectory
import lighthouse.nav.Activity
import lighthouse.protocol.Project
import lighthouse.subwindows.EditProjectWindow
import lighthouse.threading.AffinityExecutor
import lighthouse.utils.GuiUtils
import lighthouse.utils.GuiUtils.getResource
import lighthouse.utils.GuiUtils.platformFiddleChooser
import lighthouse.utils.I18nUtil
import lighthouse.utils.I18nUtil.tr
import lighthouse.utils.easing.EasingMode
import lighthouse.utils.easing.ElasticInterpolator
import lighthouse.utils.timeIt
import lighthouse.utils.ui
import nl.komponents.kovenant.async
import org.bitcoinj.core.Sha256Hash
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * An activity that shows the projects that have been loaded into the app, and buttons to import/create a new one.
 * Also can have adverts for stuff in the Lighthouse edition.
 */
public class OverviewActivity : VBox(), Activity {
    @FXML var addProjectIcon: Label? = null

    private val projects: ObservableList<Project> = Main.backend.mirrorProjects(AffinityExecutor.UI_THREAD)
    private val projectStates: ObservableMap<Sha256Hash, LighthouseBackend.ProjectStateInfo> = Main.backend.mirrorProjectStates(AffinityExecutor.UI_THREAD)
    // A map indicating the status of checking each project against the network (downloading, found an error, done, etc)
    // This is mirrored into the UI thread from the backend.
    private val checkStates: ObservableMap<Project, LighthouseBackend.CheckStatus> = Main.backend.mirrorCheckStatuses(AffinityExecutor.UI_THREAD)
    private val numInitialBoxes: Int

    init {
        val loader = FXMLLoader(getResource("activities/overview.fxml"), I18nUtil.translations)
        loader.setRoot(this)
        loader.setController(this)
        loader.load<Any>()

        numInitialBoxes = children.size()

        AwesomeDude.setIcon(addProjectIcon, AwesomeIcon.FILE_ALT, "50pt; -fx-text-fill: white" /* lame hack */)

        // 4 projects is enough to fill the window on most screens.
        val PRERENDER = 4

        val pr = projects.reversed()
        val immediate = pr.take(PRERENDER)
        val later = pr.drop(PRERENDER)

        fun addWidget(w: ProjectOverviewWidget) = children.add(children.size() - numInitialBoxes, w)

        // Attempting to parallelize this didn't work: when interpreted it takes ~500msec to load a project.
        // When compiled it is 10x faster. So we're already blasting away on the other CPU cores to compile
        // this and if we do all builds in parallel, we end up slower not faster because every load takes 500msec :(
        for (project in immediate)
            timeIt("build project widget for ${project.title}") { addWidget(buildProjectWidget(project)) }

        // And do the rest in the background
        async(ui) {
            later.map { buildProjectWidget(it) }
        } success {
            for (widget in it) addWidget(widget)

            projects.addListener(ListChangeListener<Project> { change: ListChangeListener.Change<out Project> ->
                while (change.next()) when {
                    change.wasReplaced() -> updateExistingProject(change.from, (change.addedSubList as List<Project>)[0])

                    change.wasAdded() -> slideInNewProject((change.addedSubList as List<Project>)[0])

                    change.wasRemoved() ->
                        // Cannot animate project remove yet.
                        children.remove(children.size() - 1 - numInitialBoxes - change.from)
                }
            })
        }
    }

    @FXML
    fun addProjectClicked(event: ActionEvent) = EditProjectWindow.openForCreate()

    @FXML
    fun importClicked(event: ActionEvent) {
        val chooser = FileChooser()
        chooser.title = tr("Select a bitcoin project file to import")
        chooser.extensionFilters.add(FileChooser.ExtensionFilter(tr("Project/contract files"), "*" + LighthouseBackend.PROJECT_FILE_EXTENSION))
        platformFiddleChooser(chooser)
        val file = chooser.showOpenDialog(Main.instance.mainStage) ?: return
        log.info("Import clicked: $file")
        importProject(file)
    }

    @FXML
    fun dragOver(event: DragEvent) {
        var accept = false
        if (event.gestureSource != null)
            return    // Coming from us.
        for (file in event.dragboard.files) {
            val s = file.toString()
            if (s.endsWith(LighthouseBackend.PROJECT_FILE_EXTENSION) || s.endsWith(LighthouseBackend.PLEDGE_FILE_EXTENSION)) {
                accept = true
                break
            }
        }
        if (accept)
            event.acceptTransferModes(*TransferMode.COPY_OR_MOVE)
    }

    @FXML
    fun dragDropped(event: DragEvent) {
        log.info("Drop: {}", event)
        for (file in event.dragboard.files)
            handleOpenedFile(file)
    }

    public fun handleOpenedFile(file: File) {
        // Can be called either due to a drop, or user double clicking a file in a file explorer.

        // TODO: What happens if this is called whilst the overview isn't on screen?

        GuiUtils.checkGuiThread()
        log.info("Opening {}", file)
        when {
            file.toString().endsWith(LighthouseBackend.PROJECT_FILE_EXTENSION) -> importProject(file)
            file.toString().endsWith(LighthouseBackend.PLEDGE_FILE_EXTENSION) -> importPledge(file)

            else -> log.error("Unknown file type open requested: should not happen: " + file)
        }
    }

    public fun importPledge(file: File) {
        try {
            val hash = Sha256Hash.of(file)
            Files.copy(file.toPath(), AppDirectory.dir().resolve(hash.toString() + LighthouseBackend.PLEDGE_FILE_EXTENSION))
        } catch (e: IOException) {
            GuiUtils.informationalAlert(tr("Import failed"), // TRANS: %1$s = app name, %2$s = error message
                    tr("Could not copy the dropped pledge into the %1\$s application directory: %2\$s"), Main.APP_NAME, e)
        }
    }

    public fun importProject(file: File) {
        importProject(file.toPath())
    }

    public fun importProject(file: Path) {
        try {
            Main.backend.importProjectFrom(file)
        } catch (e: Exception) {
            GuiUtils.informationalAlert(tr("Failed to import project"), // TRANS: %s = error message
                    tr("Could not read project file: %s"), e.getMessage())
        }

    }

    // Triggered by the projects list being touched by the backend.
    private fun updateExistingProject(index: Int, newProject: Project) {
        log.info("Update at index $index")
        val uiIndex = children.size() - 1 - numInitialBoxes - index
        check(uiIndex >= 0)
        children.set(uiIndex, buildProjectWidget(newProject))
    }

    private fun buildProjectWidget(project: Project): ProjectOverviewWidget {
        val state = SimpleObjectProperty(getProjectState(project))

        val projectWidget: ProjectOverviewWidget
        // TODO: Fix this offline handling.
        if (Main.bitcoin.isOffline()) {
            state.set(LighthouseBackend.ProjectState.UNKNOWN)
            projectWidget = ProjectOverviewWidget(project, SimpleLongProperty(0), state)
        } else {
            projectStates.addListener(InvalidationListener { state.set(getProjectState(project)) })
            projectWidget = ProjectOverviewWidget(project, Main.backend.makeTotalPledgedProperty(project, AffinityExecutor.UI_THREAD), state)
            projectWidget.styleClass.add("project-overview-widget-clickable")
            projectWidget.onCheckStatusChanged(checkStates.get(project))
            checkStates.addListener(MapChangeListener<Project, LighthouseBackend.CheckStatus> { change ->
                if (change.key == project)
                    projectWidget.onCheckStatusChanged(if (change.wasAdded()) change.valueAdded else null)
            })
            projectWidget.addEventHandler<MouseEvent>(MouseEvent.MOUSE_CLICKED) {
                log.info("Switching to project: {}", project.title)
                val activity = ProjectActivity(projects, project, checkStates)
                MainWindow.navManager.navigate(activity)
            }
        }
        return projectWidget
    }

    // Triggered by the project disk model being adjusted.
    private fun slideInNewProject(project: Project) {
        val sp = MainWindow.navManager.scrollPane
        if (sp.vvalue != sp.vmin) {
            // Need to scroll to the top before dropping the project widget in.
            scrollToTop().setOnFinished() {
                slideInNewProject(project)
            }
            return
        }
        val projectWidget = buildProjectWidget(project)

        // Hack: Add at the end for the size calculation, then we'll move it to the start after the next frame.
        projectWidget.isVisible = false
        children.add(projectWidget)

        // Slide in from above.
        Platform.runLater() {
            var amount = projectWidget.height
            amount += spacing
            translateY = -amount
            val transition = TranslateTransition(Duration.millis(1500.0), this)
            transition.fromY = -amount
            transition.toY = 0.0
            transition.interpolator = ElasticInterpolator(EasingMode.EASE_OUT)
            transition.delay = Duration.millis(1000.0)
            transition.play()
            // Re-position at the start.
            children.remove(projectWidget)
            children.add(0, projectWidget)
            projectWidget.isVisible = true
        }
    }

    private fun scrollToTop(): Animation {
        val animation = Timeline(
                KeyFrame(
                        GuiUtils.UI_ANIMATION_TIME,
                        KeyValue(
                                MainWindow.navManager.scrollPane.vvalueProperty() as WritableValue<Any>,  // KT-6581
                                MainWindow.navManager.scrollPane.vmin,
                                Interpolator.EASE_BOTH
                        )
                )
        )
        animation.play()
        return animation
    }

    private fun getProjectState(p: Project) = projectStates[p.idHash]?.state ?: LighthouseBackend.ProjectState.OPEN

    override fun onStart() {}

    override fun onStop() {}

    private val log = LoggerFactory.getLogger(OverviewActivity::class.java)
}
