package lighthouse.model;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.Coin;
import com.google.bitcoin.script.ScriptBuilder;
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

    private LHProtos.ProjectDetails.Builder proto;

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
        goalAmount.addListener(o -> proto.getOutputsBuilder(0).setAmount(goalAmount.longValue()));

        serverName.addListener(o -> {
            final String name = serverName.get();
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
}
