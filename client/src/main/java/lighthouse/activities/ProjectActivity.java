package lighthouse.activities;

import com.google.common.base.*;
import com.google.common.collect.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.binding.*;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.collections.transformation.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.effect.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import lighthouse.*;
import lighthouse.controls.*;
import lighthouse.nav.*;
import lighthouse.protocol.*;
import lighthouse.subwindows.*;
import lighthouse.threading.*;
import lighthouse.utils.*;
import org.bitcoinj.core.*;
import org.bitcoinj.params.*;
import org.slf4j.*;

import javax.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;

import static com.google.common.base.Preconditions.*;
import static javafx.beans.binding.Bindings.*;
import static javafx.collections.FXCollections.*;
import static lighthouse.utils.GuiUtils.*;
import static lighthouse.utils.I18nUtil.*;
import static lighthouse.utils.MoreBindings.*;

/**
 * The main content area that shows project details, pledges, a pie chart, buttons etc.
 */
public class ProjectActivity extends HBox implements Activity {
    private static final Logger log = LoggerFactory.getLogger(ProjectActivity.class);

    private static final String BLOCK_EXPLORER_SITE = "https://insight.bitpay.com/tx/%s";
    private static final String BLOCK_EXPLORER_SITE_TESTNET = "https://www.biteasy.com/testnet/transactions/%s";

    @FXML Label projectTitle;
    @FXML Label goalAmountLabel;
    @FXML Label raisedAmountLabel;
    @FXML MarkDownNode description;
    @FXML ListView<LHProtos.Pledge> pledgesList;
    @FXML PieChart pieChart;
    @FXML Button actionButton;
    @FXML Pane coverImage;
    @FXML Label numPledgersLabel;
    @FXML Label percentFundedLabel;
    @FXML Button editButton;
    @FXML Label copyDescriptionLink;

    public final ObjectProperty<Project> project = new SimpleObjectProperty<>();

    private PieChart.Data emptySlice;
    private ObservableSet<LHProtos.Pledge> pledges;
    private UIBindings bindings;
    private LongProperty pledgedValue;
    private ObjectBinding<LighthouseBackend.CheckStatus> checkStatus;
    private ObservableMap<String, LighthouseBackend.ProjectStateInfo> projectStates;  // project id -> status
    private ObservableMap<Project, LighthouseBackend.CheckStatus> statusMap;
    @Nullable private NotificationBarPane.Item notifyBarItem;
    @Nullable private Sha256Hash myPledgeHash;

    private String goalAmountFormatStr;
    private BooleanBinding isFullyFundedAndNotParticipating;

    private enum Mode {
        OPEN_FOR_PLEDGES,
        PLEDGED,
        CAN_CLAIM,
        CLAIMED,
    }
    private SimpleObjectProperty<Mode> mode = new SimpleObjectProperty<>(Mode.OPEN_FOR_PLEDGES);
    private Mode priorMode;

    public ProjectActivity(ObservableList<Project> projects, Project project, ObservableMap<Project, LighthouseBackend.CheckStatus> statusMap) {
        // Don't try and access Main.backend here in case you race with startup.
        try {
            this.statusMap = statusMap;

            FXMLLoader loader = new FXMLLoader(getResource("activities/project.fxml"), I18nUtil.translations);
            loader.setRoot(this);
            loader.setController(this);
            loader.load();

            description.setUrlOpener(url -> Main.instance.getHostServices().showDocument(url));
            goalAmountFormatStr = goalAmountLabel.getText();
            pledgesList.setCellFactory(pledgeListView -> new PledgeListCell());
            this.project.addListener(x -> updateForProject());

            projects.addListener((ListChangeListener<Project>) change -> {
                while (change.next()) {
                    if (change.wasReplaced()) {
                        if (getProject().equals(change.getRemoved().get(0)))
                            setProject(change.getAddedSubList().get(0));
                    }
                }
            });

            this.project.set(project);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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

//            pledges = fakePledges();
            pledges = Main.backend.mirrorOpenPledges(project.get(), AffinityExecutor.UI_THREAD);
            pledges.addListener((SetChangeListener<? super LHProtos.Pledge>) change -> {
                if (change.wasAdded())
                    checkForMyPledge(project.get());
            });

            final long goalAmount = project.get().getGoalAmount().value;

            //    - Bind the amount pledged to the label.
            pledgedValue = LighthouseBackend.Companion.bindTotalPledgedProperty(pledges);
            raisedAmountLabel.textProperty().bind(createStringBinding(() -> Coin.valueOf(pledgedValue.get()).toPlainString(), pledgedValue));

            numPledgersLabel.textProperty().bind(Bindings.size(pledges).asString());
            StringExpression format = Bindings.format("%.0f%%", pledgedValue.divide(1.0 * goalAmount).multiply(100.0));
            percentFundedLabel.textProperty().bind(format);

            //    - Make the action button update when the amount pledged changes.
            isFullyFundedAndNotParticipating =
                    pledgedValue.isEqualTo(project.get().getGoalAmount().longValue()).and(
                            mode.isEqualTo(Mode.OPEN_FOR_PLEDGES)
                    );
            pledgedValue.addListener(o -> pledgedValueChanged(goalAmount, pledgedValue));
            pledgedValueChanged(goalAmount, pledgedValue);
            actionButton.disableProperty().bind(isFullyFundedAndNotParticipating);

            //    - Put pledges into the list view.
            ObservableList<LHProtos.Pledge> list1 = FXCollections.observableArrayList();
            bindSetToList(pledges, list1);
            sortedByTime = new SortedList<>(list1, (o1, o2) -> -Long.compareUnsigned(o1.getPledgeDetails().getTimestamp(), o2.getPledgeDetails().getTimestamp()));
            bindContent(pledgesList.getItems(), sortedByTime);
            pledgesList.prefHeightProperty().bind(Bindings.size(sortedByTime).multiply(75).add(10));  // +10 for misc extra pixels for focus etc.

            //    - Convert pledges into pie slices.
            MappedList<PieChart.Data, LHProtos.Pledge> pledgeSlices = new MappedList<>(sortedByTime,
                    pledge -> new PieChart.Data("", pledge.getPledgeDetails().getTotalInputValue()));

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

    public void onStart() {
        if (project.get() == null) return;
        // Make the info bar appear if there's an error
        checkStatus = valueAt(statusMap, project);
        checkStatus.addListener(o -> updateInfoBar());
        // Don't let the user perform an action whilst loading or if there's an error, unless that action would
        // be revoke: users must be able to revoke even if the server is dead.
        actionButton.disableProperty().unbind();
        actionButton.disableProperty().bind(
                isFullyFundedAndNotParticipating.or(
                        checkStatus.isNotNull().and(mode.isNotEqualTo(Mode.PLEDGED))
                )
        );
        updateInfoBar();
    }

    public void onStop() {
        if (project.get() == null) return;
        if (notifyBarItem != null) {
            notifyBarItem.cancel();
            notifyBarItem = null;
        }
    }

    private void updateForProject() {
        pieChart.getData().clear();
        pledgesList.getItems().clear();

        final Project p = project.get();

        projectTitle.setText(p.getTitle());
        goalAmountLabel.setText(String.format(goalAmountFormatStr, p.getGoalAmount().toPlainString()));

        description.setText(project.get().getMemo());

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
        emptySlice.getNode().setOpacity(0.1);
        emptySlice.getNode().setVisible(true);

        checkForMyPledge(p);

        editButton.setVisible(Main.wallet.isProjectMine(p));

        // If a cloned wallet double spends our pledge, the backend can notice this before the wallet does.
        // Because the decision on what the button action should be depends on whether the wallet thinks it's pledged,
        // we have to watch out for this and update the mode here.
        Main.wallet.addOnRevokeHandler(pledge -> setModeFor(p, pledgedValue.get()), Platform::runLater);

        if (p.getPaymentURL() != null) {
            Platform.runLater(() -> {
                Main.instance.scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+R"), () -> Main.backend.refreshProjectStatusFromServer(p));
            });
        }

        applyCss();
        layout();
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
        if (status != null && status.getError() != null) {
            String msg = status.getError().getLocalizedMessage();
            if (status.getError() instanceof FileNotFoundException)
                msg = tr("Project is not on the server yet: email the project file to the operator");
            else if (status.getError() instanceof Ex.InconsistentUTXOAnswers)
                msg = tr("Bitcoin P2P network returned inconsistent answers, please contact support");
            else if (status.getError() instanceof TimeoutException)
                msg = tr("Server error: Timed out");
            else //noinspection ConstantConditions
                if (msg == null)
                    msg = tr("Internal error: ") + status.getError().getClass().getName();
            else
                // TRANS: %s = error message
                msg = String.format(tr("Error: %s"), msg);
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
        switch (mode.get()) {
            case OPEN_FOR_PLEDGES:
                if (isFullyFundedAndNotParticipating.get()) {
                    actionButton.setText(tr("Fully funded"));
                    // Disable state is handled by binding.
                } else {
                    actionButton.setText(tr("Pledge"));
                }
                break;
            case PLEDGED:
                actionButton.setText(tr("Revoke"));
                break;
            case CAN_CLAIM:
                actionButton.setText(tr("Claim"));
                break;
            case CLAIMED:
                actionButton.setText(tr("View claim transaction"));
                ColorAdjust effect = new ColorAdjust();
                coverImage.setEffect(effect);
                if (priorMode == Mode.CLAIMED) {
                    effect.setSaturation(-0.9);
                } else {
                    Timeline timeline = new Timeline(new KeyFrame(GuiUtils.UI_ANIMATION_TIME.multiply(3), new KeyValue(effect.saturationProperty(), -0.9)));
                    timeline.play();
                }
                break;
        }
    }

    private void setModeFor(Project project, long value) {
        priorMode = mode.get();
        Mode newMode = Mode.OPEN_FOR_PLEDGES;
        if (projectStates.get(project.getID()).getState() == LighthouseBackend.ProjectState.CLAIMED) {
            newMode = Mode.CLAIMED;
        } else {
            if (Main.wallet.getPledgedAmountFor(project) > 0)
                newMode = Mode.PLEDGED;
            if (value >= project.getGoalAmount().value && Main.wallet.isProjectMine(project))
                newMode = Mode.CAN_CLAIM;
        }
        log.info("Mode is {}", newMode);
        mode.set(newMode);
        if (priorMode == null) priorMode = newMode;
        updateGUIForState();
    }

    private ObservableSet<LHProtos.Pledge> fakePledges() {
        ImmutableList.Builder<LHProtos.Pledge> list = ImmutableList.builder();
        LHProtos.Pledge.Builder builder = LHProtos.Pledge.newBuilder();
        builder.getPledgeDetailsBuilder().setProjectId("abc");

        long now = Instant.now().getEpochSecond();

        for (int i = 0; i < 1; i++) {
            builder.getPledgeDetailsBuilder().setTotalInputValue(Coin.CENT.value * 70);
            builder.getPledgeDetailsBuilder().setTimestamp(now++);
            builder.getPledgeDetailsBuilder().setContactAddress("pinkponies87@gmail.com");
            builder.getPledgeDetailsBuilder().setMemo("Great idea! I'll have the t-shirt please!");
            list.add(builder.build());
            builder.getPledgeDetailsBuilder().setTotalInputValue(Coin.CENT.value * 30);
            builder.getPledgeDetailsBuilder().setTimestamp(now++);
            builder.getPledgeDetailsBuilder().setContactAddress("satoshin@gmx.com");
            builder.getPledgeDetailsBuilder().setMemo("There’s always going to be one more thing to do.");
            list.add(builder.build());
            builder.getPledgeDetailsBuilder().setTotalInputValue(Coin.CENT.value * 20);
            builder.getPledgeDetailsBuilder().setTimestamp(now++);
            builder.getPledgeDetailsBuilder().setContactAddress("bill.gates@microsoft.com");
            builder.getPledgeDetailsBuilder().setMemo("Charity begins at home");
            list.add(builder.build());
            builder.getPledgeDetailsBuilder().setTotalInputValue(Coin.CENT.value * 10);
            builder.getPledgeDetailsBuilder().setTimestamp(now++);
            builder.getPledgeDetailsBuilder().setContactAddress("hearn@vinumeris.com");
            builder.getPledgeDetailsBuilder().setMemo("My evil plan is working!!!1!");
            list.add(builder.build());
        }
        ObservableSet<LHProtos.Pledge> set = FXCollections.observableSet(new HashSet<>(list.build()));
        Main.instance.scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+P"), () -> {
            LHProtos.Pledge.Builder pledge = LHProtos.Pledge.newBuilder();
            pledge.getPledgeDetailsBuilder().setProjectId("abc");
            pledge.getPledgeDetailsBuilder().setTotalInputValue(Coin.CENT.value * 110);
            pledge.getPledgeDetailsBuilder().setTimestamp(Instant.now().getEpochSecond());
            set.add(pledge.build());
        });
        return set;
    }

    @FXML
    private void actionClicked(ActionEvent event) {
        final Project p = project.get();
        switch (mode.get()) {
            case OPEN_FOR_PLEDGES:
                if (Main.wallet.getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE).value == 0)
                    Main.instance.mainWindow.tellUserToSendSomeMoney();
                else
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
        checkState(info.getState() == LighthouseBackend.ProjectState.CLAIMED);
        String url = String.format(Main.params == TestNet3Params.get() ? BLOCK_EXPLORER_SITE_TESTNET : BLOCK_EXPLORER_SITE, info.getClaimedBy());
        log.info("Opening {}", url);
        Main.instance.getHostServices().showDocument(url);
    }

    private void makePledge(Project p) {
        log.info("Invoking pledge screen");
        PledgeWindow window = Main.instance.<PledgeWindow>overlayUI("subwindows/pledge.fxml", tr("Pledge")).controller;
        window.setProject(p);
        window.setLimits(p.getGoalAmount().subtract(Coin.valueOf(pledgedValue.get())), p.getMinPledgeAmount());
        window.onSuccess = () -> {
            mode.set(Mode.PLEDGED);
            updateGUIForState();
        };
    }

    private void claimPledges(Project p) {
        log.info("Claim button clicked for {}", p);
        Main.OverlayUI<RevokeAndClaimWindow> overlay = RevokeAndClaimWindow.openForClaim(p, pledges);
        overlay.controller.onSuccess = () -> {
            mode.set(Mode.OPEN_FOR_PLEDGES);
            updateGUIForState();
        };
    }

    private void revokePledge(Project project) {
        log.info("Revoke button clicked: {}", project.getTitle());
        LHProtos.Pledge pledge = Main.wallet.getPledgeFor(project);
        checkNotNull(pledge, "UI invariant violation");   // Otherwise our UI is really messed up.

        Main.OverlayUI<RevokeAndClaimWindow> overlay = RevokeAndClaimWindow.openForRevoke(pledge);
        overlay.controller.onSuccess = () -> {
            mode.set(Mode.OPEN_FOR_PLEDGES);
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
        private Label status, name, memoSnippet, date;
        private Label viewMore;

        public PledgeListCell() {
            Pane pane;
            HBox hbox;
            VBox vbox = new VBox(
                    (status = new Label()),
                    (hbox = new HBox(
                            (name = new Label()),
                            (pane = new Pane()),
                            (date = new Label())
                    )),
                    (memoSnippet = new Label()),
                    (viewMore = new Label(tr("View more")))
            );
            vbox.getStyleClass().add("pledge-cell");
            status.getStyleClass().add("pledge-cell-status");
            name.getStyleClass().add("pledge-cell-name");
            HBox.setHgrow(pane, Priority.ALWAYS);
            vbox.setFillWidth(true);
            hbox.maxWidthProperty().bind(vbox.widthProperty());
            date.getStyleClass().add("pledge-cell-date");
            date.setMinWidth(USE_PREF_SIZE);    // Date is shown in preference to contact if contact data is too long
            memoSnippet.getStyleClass().add("pledge-cell-memo");
            memoSnippet.setWrapText(true);
            memoSnippet.maxWidthProperty().bind(vbox.widthProperty());
            memoSnippet.setMaxHeight(100);
            viewMore.getStyleClass().add("hover-link");
            viewMore.setOnMouseClicked(ev -> ShowPledgeWindow.open(project.get(), getItem()));
            viewMore.setAlignment(Pos.CENTER_RIGHT);
            viewMore.prefWidthProperty().bind(vbox.widthProperty());
            vbox.setPrefHeight(0);
            vbox.setMaxHeight(USE_PREF_SIZE);
            setGraphic(vbox);
        }

        @Override
        protected void updateItem(LHProtos.Pledge pledge, boolean empty) {
            super.updateItem(pledge, empty);
            if (empty) {
                getGraphic().setVisible(false);
                setOnMouseClicked(null);
                return;
            }
            setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2)
                    ShowPledgeWindow.open(project.get(), getItem());
            });
            getGraphic().setVisible(true);
            LHProtos.PledgeDetails details = pledge.getPledgeDetails();
            String msg = Coin.valueOf(details.getTotalInputValue()).toFriendlyString();
            if (LHUtils.hashFromPledge(pledge).equals(myPledgeHash))
                msg += " (yours)";
            status.setText(msg);
            name.setText(details.hasName() ? details.getName() : tr("Anonymous"));
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime time = LocalDateTime.ofEpochSecond(details.getTimestamp(), 0, ZoneOffset.UTC);
            date.setText(time.format(formatter));
            memoSnippet.setText(details.getMemo());
        }
    }

    @FXML
    public void edit(ActionEvent event) {
        log.info("Edit button clicked");
        if (pledgedValue.get() > 0) {
            informationalAlert(tr("Unable to edit"),
                    tr("You cannot edit a project that has already started gathering pledges, as otherwise existing " +
                            "pledges could be invalidated and participants could get confused. If you would like to " +
                            "change this project either create a new one, or request revocation of existing pledges.")
            );
            return;
        }
        EditProjectWindow.openForEdit(project.get());
    }

    @FXML
    public void onViewTechDetailsClicked(MouseEvent event) {
        log.info("View tech details of project clicked for {}", project.get().getTitle());
        ProjectTechDetailsWindow.open(project.get());
    }

    @FXML
    public void onCopyDescriptionClicked(MouseEvent event) {
        log.info("Copy description to clipboard clicked");
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(project.get().getMemo());
        clipboard.setContent(content);
        GuiUtils.arrowBubbleToNode(copyDescriptionLink, tr("Description copied to clipboard"));
    }

    @FXML
    public void exportPledgesClicked(MouseEvent event) {
        Project project = this.project.get();
        log.info("Export pledges clicked for {}", project.getTitle());
        if (Main.wallet.isEncrypted()) {
            log.info("Wallet is encrypted, requesting password");
            WalletPasswordController.requestPassword(key -> {
                // TODO: Should really throw something up on the screen here.
                Main.instance.scene.setCursor(Cursor.WAIT);
                project.getStatus(Main.wallet, key).handleAsync((status, ex) -> {
                    Main.instance.scene.setCursor(Cursor.DEFAULT);
                    if (ex != null) {
                        log.error("Unable to fetch project status", ex);
                        informationalAlert(tr("Unable to fetch email addresses"),
                            // TRANS: %s = error message
                            tr("Could not fetch project status from server: %s"), ex);
                    } else {
                        exportPledges(status.getPledgesList());
                    }
                    return null;
                }, Platform::runLater);
            });
        } else {
            exportPledges(pledgesList.getItems());
        }
    }

    private void exportPledges(List<LHProtos.Pledge> pledges) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("Export pledges to CSV file"));
        chooser.setInitialFileName("pledges.csv");
        GuiUtils.platformFiddleChooser(chooser);
        File file = chooser.showSaveDialog(Main.instance.mainStage);
        if (file == null) {
            log.info(" ... but user cancelled");
            return;
        }
        log.info("Saving pledges as CSV to file {}", file);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file.toPath()), Charsets.UTF_8)) {
            writer.append(String.format("num_satoshis,time,name,email,message%n"));
            for (LHProtos.Pledge pledge : pledges) {
                String time = Instant.ofEpochSecond(pledge.getPledgeDetails().getTimestamp()).atZone(ZoneId.of("UTC")).format(DateTimeFormatter.RFC_1123_DATE_TIME).replace(",", "");
                String memo = pledge.getPledgeDetails().getMemo().replace('\n', ' ').replace(",", "");
                writer.append(String.format("%d,%s,%s,%s,%s%n", pledge.getPledgeDetails().getTotalInputValue(),
                        time, pledge.getPledgeDetails().getName(), pledge.getPledgeDetails().getContactAddress(), memo));
            }
            GuiUtils.informationalAlert(tr("Export succeeded"), tr("Pledges are stored in a CSV file, which can be loaded with any spreadsheet application. Amounts are specified in satoshis."));
        } catch (IOException e) {
            log.error("Failed to write to csv file", e);
            GuiUtils.informationalAlert(tr("Export failed"),
                // TRANS: %s = error message
                tr("Lighthouse was unable to save pledge data to the selected file: %s"), e.getLocalizedMessage());
        }
    }
}
