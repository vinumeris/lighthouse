package lighthouse.controls;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.binding.StringExpression;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.*;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import lighthouse.LighthouseBackend;
import lighthouse.Main;
import lighthouse.protocol.Ex;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.LHUtils;
import lighthouse.protocol.Project;
import lighthouse.subwindows.EditProjectWindow;
import lighthouse.subwindows.PledgeWindow;
import lighthouse.subwindows.RevokeAndClaimWindow;
import lighthouse.subwindows.ShowPledgeWindow;
import lighthouse.threading.AffinityExecutor;
import lighthouse.utils.ConcatenatingList;
import lighthouse.utils.GuiUtils;
import lighthouse.utils.MappedList;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.params.TestNet3Params;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static javafx.beans.binding.Bindings.*;
import static javafx.collections.FXCollections.singletonObservableList;
import static lighthouse.utils.GuiUtils.getResource;
import static lighthouse.utils.GuiUtils.informationalAlert;
import static lighthouse.utils.MoreBindings.bindSetToList;
import static lighthouse.utils.MoreBindings.mergeSets;

/**
 * The main content area that shows project details, pledges, a pie chart, buttons etc.
 */
public class ProjectView extends HBox {
    private static final Logger log = LoggerFactory.getLogger(ProjectView.class);

    private static final String BLOCK_EXPLORER_SITE = "https://www.biteasy.com/blockchain/transactions/%s";
    private static final String BLOCK_EXPLORER_SITE_TESTNET = "https://www.biteasy.com/testnet/transactions/%s";

    @FXML Label projectTitle;
    @FXML Label goalAmountLabel;
    @FXML Label raisedAmountLabel;
    @FXML TextFlow description;
    @FXML Label noPledgesLabel;
    @FXML ListView<LHProtos.Pledge> pledgesList;
    @FXML PieChart pieChart;
    @FXML Button actionButton;
    @FXML Pane coverImage;
    @FXML Label numPledgersLabel;
    @FXML Label percentFundedLabel;
    @FXML Button editButton;

    public final ObjectProperty<Project> project = new SimpleObjectProperty<>();
    public final ObjectProperty<EventHandler<ActionEvent>> onBackClickedProperty = new SimpleObjectProperty<>();

    private PieChart.Data emptySlice;
    private final KeyCombination backKey = KeyCombination.valueOf("Shortcut+LEFT");
    private ObservableSet<LHProtos.Pledge> pledges;
    private UIBindings bindings;
    private LongProperty pledgedValue;
    private ObjectBinding<LighthouseBackend.CheckStatus> checkStatus;
    private ObservableMap<String, LighthouseBackend.ProjectStateInfo> projectStates;  // project id -> status
    @Nullable private NotificationBarPane.Item notifyBarItem;

    @Nullable private Sha256Hash myPledgeHash;

    private String goalAmountFormatStr;

    private enum Mode {
        OPEN_FOR_PLEDGES,
        PLEDGED,
        CAN_CLAIM,
        CLAIMED,
    }
    private Mode mode, priorMode;

    public ProjectView() {
        // Don't try and access Main.backend here in case you race with startup.
        setupFXML();
        pledgesList.setCellFactory(pledgeListView -> new PledgeListCell());
        project.addListener(x -> updateForProject());
    }

    // Holds together various bindings so we can disconnect them when we switch projects.
    private class UIBindings {
        private final ObservableList<LHProtos.Pledge> sortedByTime;
        private final ConcatenatingList<PieChart.Data> slices;

        public UIBindings() {
            // Bind the project pledges from the backend to the UI components so they react appropriately.
            projectStates = Main.backend.mirrorProjectStates(AffinityExecutor.UI_THREAD);
            projectStates.addListener((javafx.beans.InvalidationListener) x -> {
                setModeFor(project.get(), pledgedValue.get());
            });

            //pledges = fakePledges();
            ObservableSet<LHProtos.Pledge> openPledges = Main.backend.mirrorOpenPledges(project.get(), AffinityExecutor.UI_THREAD);
            ObservableSet<LHProtos.Pledge> claimedPledges = Main.backend.mirrorClaimedPledges(project.get(), AffinityExecutor.UI_THREAD);
            pledges = mergeSets(openPledges, claimedPledges);
            pledges.addListener((SetChangeListener<? super LHProtos.Pledge>) change -> {
                if (change.wasAdded())
                    checkForMyPledge(project.get());
            });

            final long goalAmount = project.get().getGoalAmount().value;

            //    - Bind the amount pledged to the label.
            pledgedValue = LighthouseBackend.bindTotalPledgedProperty(pledges);
            raisedAmountLabel.textProperty().bind(createStringBinding(() -> Coin.valueOf(pledgedValue.get()).toPlainString(), pledgedValue));

            numPledgersLabel.textProperty().bind(Bindings.size(pledges).asString());
            StringExpression format = Bindings.format("%.0f%%", pledgedValue.divide(1.0 * goalAmount).multiply(100.0));
            percentFundedLabel.textProperty().bind(format);

            //    - Make the action button update when the amount pledged changes.
            pledgedValue.addListener(o -> pledgedValueChanged(goalAmount, pledgedValue));
            pledgedValueChanged(goalAmount, pledgedValue);

            //    - Put pledges into the list view.
            ObservableList<LHProtos.Pledge> list1 = FXCollections.observableArrayList();
            bindSetToList(pledges, list1);
            sortedByTime = new SortedList<>(list1, (o1, o2) -> Long.compareUnsigned(o1.getTimestamp(), o2.getTimestamp()));
            bindContent(pledgesList.getItems(), sortedByTime);

            //    - Convert pledges into pie slices.
            MappedList<PieChart.Data, LHProtos.Pledge> pledgeSlices = new MappedList<>(sortedByTime,
                    pledge -> new PieChart.Data("", pledge.getTotalInputValue()));

            //    - Stick an invisible padding slice on the end so we can see through the unpledged part.
            slices = new ConcatenatingList<>(pledgeSlices, singletonObservableList(emptySlice));

            //    - Connect to the chart widget.
            bindContent(pieChart.getData(), slices);
        }

        public void unbind() {
            numPledgersLabel.textProperty().unbind();
            percentFundedLabel.textProperty().unbind();
            unbindContent(pledgesList.getItems(), sortedByTime);
            unbindContent(pieChart.getData(), slices);
        }
    }

    public void updateForVisibility(boolean visible, @Nullable ObservableMap<Project, LighthouseBackend.CheckStatus> statusMap) {
        if (project.get() == null) return;
        if (visible) {
            // Put the back keyboard shortcut in later, because removing an accelerator whilst a callback is being
            // processed causes a ConcurrentModificationException inside the framework before 8u20.
            Platform.runLater(() -> Main.instance.scene.getAccelerators().put(backKey, () -> backClicked(null)));
            // Make the info bar appear if there's an error
            checkStatus = valueAt(statusMap, project);
            checkStatus.addListener(o -> updateInfoBar());
            // Don't let the user perform an action whilst loading or if there's an error.
            actionButton.disableProperty().unbind();
            actionButton.disableProperty().bind(checkStatus.isNotNull());
            updateInfoBar();
        } else {
            // Take the back keyboard shortcut out later, because removing an accelerator whilst its callback is being
            // processed causes a ConcurrentModificationException inside the framework before 8u20.
            Platform.runLater(() -> Main.instance.scene.getAccelerators().remove(backKey));
            if (notifyBarItem != null) {
                notifyBarItem.cancel();
                notifyBarItem = null;
            }
        }
    }

    private void updateForProject() {
        pieChart.getData().clear();
        pledgesList.getItems().clear();

        final Project p = project.get();

        projectTitle.setText(p.getTitle());
        goalAmountLabel.setText(String.format(goalAmountFormatStr, p.getGoalAmount().toPlainString()));

        description.getChildren().setAll(new Text(project.get().getMemo()));

        noPledgesLabel.visibleProperty().bind(isEmpty(pledgesList.getItems()));

        // Load and set up the cover image.
        Image img = new Image(p.getCoverImage().newInput());
        if (img.getException() != null)
            Throwables.propagate(img.getException());
        BackgroundSize cover = new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO, false, false, false, true);
        BackgroundImage bimg = new BackgroundImage(img, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.DEFAULT, cover);
        coverImage.setBackground(new Background(bimg));

        // Configure the pie chart.
        emptySlice = new PieChart.Data("", 0);

        if (bindings != null)
            bindings.unbind();
        bindings = new UIBindings();

        // This must be done after the binding because otherwise it has no node in the scene graph yet.
        emptySlice.getNode().setVisible(false);

        checkForMyPledge(p);

        editButton.setVisible(Main.wallet.isProjectMine(p));

        if (p.getPaymentURL() != null) {
            Platform.runLater(() -> {
                Main.instance.scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+R"), () -> Main.backend.refreshProjectStatusFromServer(p));
            });
        }
    }

    private void checkForMyPledge(Project p) {
        LHProtos.Pledge myPledge = Main.wallet.getPledgeFor(p);
        if (myPledge != null)
            myPledgeHash = LHUtils.hashFromPledge(myPledge);
    }

    private void updateInfoBar() {
        if (notifyBarItem != null)
            notifyBarItem.cancel();
        final LighthouseBackend.CheckStatus status = checkStatus.get();
        if (status != null && status.error != null) {
            String msg = status.error.getLocalizedMessage();
            if (status.error instanceof FileNotFoundException)
                msg = "Server error: 404 Not Found: project is not known";
            else if (status.error instanceof Ex.InconsistentUTXOAnswers)
                msg = "Bitcoin P2P network returned inconsistent answers, please contact support";
            else //noinspection ConstantConditions
                if (msg == null)
                    msg = "Internal error: " + status.error.getClass().getName();
            else
                msg = "Server error: " + msg;
            notifyBarItem = Main.instance.notificationBar.displayNewItem(msg);
        }
    }

    private void pledgedValueChanged(long goalAmount, LongProperty pledgedValue) {
        // Take the max so if we end up with more pledges than the goal in serverless mode, the pie chart is always
        // full and doesn't go backwards due to a negative pie slice.
        emptySlice.setPieValue(Math.max(0, goalAmount - pledgedValue.get()));
        setModeFor(project.get(), pledgedValue.get());
    }

    private void updateGUIForState() {
        coverImage.setEffect(null);
        switch (mode) {
            case OPEN_FOR_PLEDGES:
                actionButton.setText("Pledge");
                break;
            case PLEDGED:
                actionButton.setText("Revoke");
                break;
            case CAN_CLAIM:
                actionButton.setText("Claim");
                break;
            case CLAIMED:
                actionButton.setText("View claim transaction");
                ColorAdjust effect = new ColorAdjust();
                coverImage.setEffect(effect);
                if (priorMode != Mode.CLAIMED) {
                    Timeline timeline = new Timeline(new KeyFrame(GuiUtils.UI_ANIMATION_TIME.multiply(3), new KeyValue(effect.saturationProperty(), -0.9)));
                    timeline.play();
                } else {
                    effect.setSaturation(-0.9);
                }
                break;
        }
    }

    private void setModeFor(Project project, long value) {
        priorMode = mode;
        mode = Mode.OPEN_FOR_PLEDGES;
        if (projectStates.get(project.getID()).state == LighthouseBackend.ProjectState.CLAIMED) {
            mode = Mode.CLAIMED;
        } else {
            if (Main.wallet.getPledgedAmountFor(project) > 0)
                mode = Mode.PLEDGED;
            if (value >= project.getGoalAmount().value && Main.wallet.isProjectMine(project))
                mode = Mode.CAN_CLAIM;
        }
        log.info("Mode is {}", mode);
        if (priorMode == null) priorMode = mode;
        updateGUIForState();
    }

    private ObservableSet<LHProtos.Pledge> fakePledges() {
        ImmutableList.Builder<LHProtos.Pledge> list = ImmutableList.builder();
        LHProtos.Pledge.Builder builder = LHProtos.Pledge.newBuilder();
        builder.setProjectId("abc");

        long now = Instant.now().getEpochSecond();

        // Total of 1.3 coins pledged.
        for (int i = 0; i < 5; i++) {
            builder.setTotalInputValue(Coin.CENT.value * 70);
            builder.setTimestamp(now++);
            list.add(builder.build());
            builder.setTotalInputValue(Coin.CENT.value * 20);
            builder.setTimestamp(now++);
            list.add(builder.build());
            builder.setTotalInputValue(Coin.CENT.value * 10);
            builder.setTimestamp(now++);
            list.add(builder.build());
            builder.setTotalInputValue(Coin.CENT.value * 30);
            builder.setTimestamp(now++);
            list.add(builder.build());
        }
        return FXCollections.observableSet(new HashSet<>(list.build()));
    }

    private void setupFXML() {
        try {
            FXMLLoader loader = new FXMLLoader(getResource("controls/project_view.fxml"));
            loader.setRoot(this);
            loader.setController(this);
            // The following line is supposed to help Scene Builder, although it doesn't seem to be needed for me.
            loader.setClassLoader(getClass().getClassLoader());
            loader.load();

            goalAmountFormatStr = goalAmountLabel.getText();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @FXML
    private void backClicked(@Nullable ActionEvent event) {
        if (onBackClickedProperty.get() != null)
            onBackClickedProperty.get().handle(event);
    }

    @FXML
    private void actionClicked(ActionEvent event) {
        final Project p = project.get();
        switch (mode) {
            case OPEN_FOR_PLEDGES:
                makePledge(p);
                break;
            case PLEDGED:
                revokePledge(p);
                break;
            case CAN_CLAIM:
                claimPledges(p);
                break;
            case CLAIMED:
                viewClaim(p);
                break;
            default:
                throw new AssertionError();  // Unreachable.
        }
    }

    private void viewClaim(Project p) {
        LighthouseBackend.ProjectStateInfo info = projectStates.get(p.getID());
        checkState(info.state == LighthouseBackend.ProjectState.CLAIMED);
        String url = String.format(Main.params == TestNet3Params.get() ? BLOCK_EXPLORER_SITE_TESTNET : BLOCK_EXPLORER_SITE, info.claimedBy);
        log.info("Opening {}", url);
        Main.instance.getHostServices().showDocument(url);
    }

    private void makePledge(Project p) {
        log.info("Invoking pledge screen");
        PledgeWindow window = Main.instance.<PledgeWindow>overlayUI("subwindows/pledge.fxml", "Pledge").controller;
        window.project = p;
        window.setLimits(p.getGoalAmount().subtract(Coin.valueOf(pledgedValue.get())), p.getMinPledgeAmount());
        window.onSuccess = () -> {
            mode = Mode.PLEDGED;
            updateGUIForState();
        };
    }

    private void claimPledges(Project p) {
        log.info("Claim button clicked for {}", p);
        Main.OverlayUI<RevokeAndClaimWindow> overlay = RevokeAndClaimWindow.openForClaim(p, pledges);
        overlay.controller.onSuccess = () -> {
            mode = Mode.OPEN_FOR_PLEDGES;
            updateGUIForState();
        };
    }

    private void revokePledge(Project project) {
        log.info("Revoke button clicked: {}", project.getTitle());
        LHProtos.Pledge pledge = Main.wallet.getPledgeFor(project);
        checkNotNull(pledge, "UI invariant violation");   // Otherwise our UI is really messed up.

        Main.OverlayUI<RevokeAndClaimWindow> overlay = RevokeAndClaimWindow.openForRevoke(pledge);
        overlay.controller.onSuccess = () -> {
            mode = Mode.OPEN_FOR_PLEDGES;
            updateGUIForState();
        };
    }

    public void setProject(Project project) {
        this.project.set(project);
    }

    public Project getProject() {
        return this.project.get();
    }

    // TODO: Should we show revoked pledges crossed out?
    private class PledgeListCell extends ListCell<LHProtos.Pledge> {
        private Label status, email, memoSnippet, date;
        private Label viewMore;

        public PledgeListCell() {
            Pane pane;
            HBox hbox;
            VBox vbox = new VBox(
                    (status = new Label()),
                    (hbox = new HBox(
                            (email = new Label()),
                            (pane = new Pane()),
                            (date = new Label())
                    )),
                    (memoSnippet = new Label()),
                    (viewMore = new Label("View more"))
            );
            vbox.getStyleClass().add("pledge-cell");
            status.getStyleClass().add("pledge-cell-status");
            email.getStyleClass().add("pledge-cell-email");
            HBox.setHgrow(pane, Priority.ALWAYS);
            vbox.setFillWidth(true);
            hbox.maxWidthProperty().bind(vbox.widthProperty());
            date.getStyleClass().add("pledge-cell-date");
            date.setMinWidth(USE_PREF_SIZE);    // Date is shown in preference to contact if contact data is too long
            memoSnippet.getStyleClass().add("pledge-cell-memo");
            memoSnippet.setWrapText(true);
            memoSnippet.maxWidthProperty().bind(vbox.widthProperty());
            memoSnippet.setMaxHeight(100);
            viewMore.setStyle("-fx-text-fill: blue; -fx-cursor: hand");
            viewMore.setOnMouseClicked(ev -> ShowPledgeWindow.open(getItem()));
            viewMore.setAlignment(Pos.CENTER_RIGHT);
            viewMore.prefWidthProperty().bind(vbox.widthProperty());
            setGraphic(vbox);
            setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2)
                    ShowPledgeWindow.open(getItem());
            });
        }

        @Override
        protected void updateItem(LHProtos.Pledge pledge, boolean empty) {
            super.updateItem(pledge, empty);
            if (empty) {
                getGraphic().setVisible(false);
                return;
            }
            getGraphic().setVisible(true);
            String msg = Coin.valueOf(pledge.getTotalInputValue()).toFriendlyString();
            if (LHUtils.hashFromPledge(pledge).equals(myPledgeHash))
                msg += " (yours)";
            status.setText(msg);
            email.setText(pledge.getPledgeDetails().getContactAddress());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime time = LocalDateTime.ofEpochSecond(pledge.getTimestamp(), 0, ZoneOffset.UTC);
            date.setText(time.format(formatter));
            memoSnippet.setText(pledge.getPledgeDetails().getMemo());
        }
    }

    @FXML
    public void edit(ActionEvent event) {
        log.info("Edit button clicked");
        if (pledgedValue.get() > 0) {
            informationalAlert("Unable to edit",
                    "You cannot edit a project that has already started gathering pledges, as otherwise existing " +
                            "pledges could be invalidated and participants could get confused. If you would like to " +
                            "change this project either create a new one, or request revocation of existing pledges."
            );
            return;
        }
        EditProjectWindow.openForEdit(project.get());
    }
}
