package lighthouse.model;

import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import com.google.protobuf.ByteString;
import javafx.beans.property.*;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.LHUtils;
import lighthouse.protocol.Project;
import lighthouse.wallet.PledgingWallet;

import static lighthouse.protocol.LHUtils.unchecked;

/**
 * This class wraps a LHProtos.Project and exposes JFX properties for things that users are interested in. It performs
 * some validation and links the properties to a protobuf builder.
 */
public class ProjectModel {
    public final StringProperty title = new SimpleStringProperty();
    public final StringProperty memo = new SimpleStringProperty();
    public final StringProperty serverName = new SimpleStringProperty();
    public final StringProperty address = new SimpleStringProperty();
    // Value in satoshis.
    public final LongProperty goalAmount = new SimpleLongProperty();
    public final ObjectProperty<ByteString> image = new SimpleObjectProperty<>();
    private final LongProperty minPledgeAmount = new SimpleLongProperty();

    private LHProtos.ProjectDetails.Builder proto;


    public static final int ESTIMATED_INPUT_SIZE = Script.SIG_SIZE + 35 /* bytes for a compressed pubkey */ + 32 /* hash */ + 4;
    public static final int MAX_NUM_INPUTS = (Transaction.MAX_STANDARD_TX_SIZE - 64) /* for output */ / ESTIMATED_INPUT_SIZE;

    public ProjectModel(PledgingWallet wallet) {
        this(Project.makeDetails("New project", "", wallet.freshReceiveAddress(), Coin.SATOSHI, wallet.freshAuthKey(),
                wallet.getKeychainLookaheadSize()));
    }

    public ProjectModel(LHProtos.ProjectDetails.Builder liveProto) {
        this.proto = liveProto;
        final LHProtos.Project.Builder wrapper = LHProtos.Project.newBuilder().setSerializedPaymentDetails(liveProto.build().toByteString());
        Project project = unchecked(() -> new Project(wrapper.build()));
        title.set(project.getTitle());
        memo.set(project.getMemo());
        goalAmount.set(project.getGoalAmount().value);

        if (liveProto.hasPaymentUrl()) {
            String host = LHUtils.validateServerPath(liveProto.getPaymentUrl(), project.getID());
            if (host == null)
                throw new IllegalArgumentException("Server path not valid for CC protocol: " + liveProto.getPaymentUrl());
            serverName.set(host);
        }

        // Connect the properties.
        title.addListener(o -> proto.getExtraDetailsBuilder().setTitle(title.get()));
        memo.addListener(o -> proto.setMemo(memo.get()));
        // Just adjust the first output. GUI doesn't handle multioutput contracts right now (they're useless anyway).
        goalAmount.addListener(o -> {
            long value = goalAmount.longValue();
            minPledgeAmount.set(recalculateMinPledgeAmount(value));
            proto.getOutputsBuilder(0).setAmount(value);
        });

        minPledgeAmount.addListener(o -> proto.getExtraDetailsBuilder().setMinPledgeSize(minPledgeAmountProperty().get()));

        serverName.addListener(o -> {
            final String name = serverName.get();
            if (name.isEmpty())
                proto.clearPaymentUrl();
            else
                proto.setPaymentUrl(LHUtils.makeServerPath(name, LHUtils.titleToUrlString(title.get())));
        });

        Address addr = project.getOutputs().get(0).getAddressFromP2PKHScript(project.getParams());
        if (addr == null)
            throw new IllegalArgumentException("Output type is not a pay to address: " + project.getOutputs().get(0));
        address.set(addr.toString());
        address.addListener(o -> {
            try {
                Address addr2 = new Address(project.getParams(), address.get());
                proto.getOutputsBuilder(0).setScript(ByteString.copyFrom(ScriptBuilder.createOutputScript(addr2).getProgram()));
            } catch (AddressFormatException e) {
                // Ignored: wait for the user to make it valid.
            }
        });

        image.addListener(o -> {
            proto.getExtraDetailsBuilder().setCoverImage(image.get());
        });
    }

    private long recalculateMinPledgeAmount(long value) {
        // This isn't a perfect estimation by any means especially as we allow P2SH outputs to be pledged, which
        // could end up gobbling up a lot of space in the contract, but it'll do for now. How many pledges can we
        // have assuming Lighthouse makes them all i.e. pay to address?
        return value / MAX_NUM_INPUTS;
    }

    public LHProtos.Project.Builder getProto() {
        LHProtos.Project.Builder proto = LHProtos.Project.newBuilder();
        proto.setSerializedPaymentDetails(getDetailsProto().build().toByteString());
        return proto;
    }

    public Project getProject() {
        return unchecked(() -> new Project(getProto().build()));
    }

    public LHProtos.ProjectDetails.Builder getDetailsProto() {
        return proto;
    }

    public Coin getMinPledgeAmount() {
        return Coin.valueOf(minPledgeAmount.get());
    }

    public ReadOnlyLongProperty minPledgeAmountProperty() {
        return minPledgeAmount;
    }
}
