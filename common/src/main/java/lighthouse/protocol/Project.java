package lighthouse.protocol;

import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.protobuf.*;
import lighthouse.files.*;
import lighthouse.wallet.*;
import org.bitcoin.protocols.payments.Protos;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.protocols.payments.*;
import org.bitcoinj.script.*;
import org.bitcoinj.wallet.*;
import org.slf4j.*;
import org.spongycastle.crypto.params.*;
import org.spongycastle.util.io.*;

import javax.annotation.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static com.google.common.base.Preconditions.*;
import static java.time.Instant.*;
import static java.util.stream.Collectors.*;
import static lighthouse.protocol.LHUtils.*;

/**
 * A Project represents something to which pledges can be made. It is serialized using an extended form of the BIP 70
 * PaymentRequest message and typically stored on disk. Given a set of pledges and a TransactionBroadcaster, a Project
 * can create a transaction that commits the payments. This class is immutable and not concerned with the storage of
 * pledges. It is not a builder or holder for UI state, look at ProjectModel for that.
 */
public class Project {
    private final Logger log = LoggerFactory.getLogger(Project.class);
    private final NetworkParameters params;
    private final LHProtos.ProjectDetails projectReq;
    // These fields should be immutable pure functions of projectReq, as we may hand back projectReq later and
    // expect that it's not been changed.
    private final ImmutableList<TransactionOutput> outputs;
    private final long goalAmount, minPledgeAmount;
    private final String title;
    @Nullable private final URI  url;
    // Projects are identified by the hash of their serialized contents. There is no canonical encoding
    // and this ID is mostly used just as a key in various maps.
    private final Sha256Hash hash;

    private final byte[] authKey;
    private final int authKeyIndex;

    public static String GET_STATUS_USER_AGENT = "";

    public Project(LHProtos.ProjectDetails details) throws PaymentProtocolException, InvalidProtocolBufferException {
        this(wrapDetails(details).build());
    }

    private static LHProtos.Project.Builder wrapDetails(LHProtos.ProjectDetails details) {
        LHProtos.Project.Builder project = LHProtos.Project.newBuilder();
        project.setSerializedPaymentDetails(details.toByteString());
        return project;
    }

    public Project(LHProtos.Project proto) throws PaymentProtocolException, InvalidProtocolBufferException {
        hash = Sha256Hash.create(proto.toByteArray());
        // "Cast" it to a regular BIP70 payment request, possibly losing data along the way, but that's OK
        // because we only want to do this to reuse the existing APIs.
        Protos.PaymentRequest paymentRequest = Protos.PaymentRequest.parseFrom(proto.toByteString());
        PaymentSession session = new PaymentSession(paymentRequest, false);
        this.outputs = ImmutableList.copyOf(session.getSendRequest().tx.getOutputs());
        this.params = session.getNetworkParameters();
        this.projectReq = LHProtos.ProjectDetails.parseFrom(proto.getSerializedPaymentDetails());
        this.goalAmount = this.projectReq.getOutputsList().stream().mapToLong(LHProtos.Output::getAmount).sum();
        this.minPledgeAmount = this.projectReq.getExtraDetails().getMinPledgeSize();
        if (this.goalAmount <= 0)
            throw new Ex.ValueMismatch(this.goalAmount);
        this.title = this.projectReq.getExtraDetails().getTitle();
        if (this.projectReq.hasPaymentUrl()) {
            try {
                url = new URI(this.projectReq.getPaymentUrl());
                if (url.getHost() == null)
                    throw new Exception();
            } catch (Exception e) {
                throw new PaymentProtocolException("Invalid URL: " + this.projectReq.getPaymentUrl());
            }
        } else {
            url = null;
        }

        if (this.projectReq.hasMerchantData()) {
            LHProtos.OwnerData ownerData = LHProtos.OwnerData.parseFrom(this.projectReq.getMerchantData());
            this.authKeyIndex = ownerData.getAuthKeyIndex();
        } else {
            this.authKeyIndex = -1;
        }
        this.authKey = this.projectReq.getExtraDetails().getAuthKey().toByteArray();
    }

    public static LHProtos.ProjectDetails.Builder makeDetails(NetworkParameters params, String title, String memo, Address to, Coin value, DeterministicKey authKey, int lookaheadSize) {
        LHProtos.ProjectDetails.Builder details = LHProtos.ProjectDetails.newBuilder();
        final long now = Utils.currentTimeSeconds();
        final long oneMonthFromNow = now + (86400 * 30);
        details.setTime(now);
        details.setExpires(oneMonthFromNow);
        details.getExtraDetailsBuilder().setTitle(title).setAuthKey(ByteString.copyFrom(authKey.getPubKey()));
        if (authKey.getChildNumber().num() > lookaheadSize) {
            LHProtos.OwnerData ownerData = LHProtos.OwnerData.newBuilder().setAuthKeyIndex(authKey.getChildNumber().num()).build();
            details.setMerchantData(ownerData.toByteString());
        }
        details.setMemo(memo);
        details.setNetwork(params.getPaymentProtocolId());
        LHProtos.Output.Builder output = details.addOutputsBuilder();
        output.setAmount(value.value);
        output.setScript(ByteString.copyFrom(ScriptBuilder.createOutputScript(to).getProgram()));
        return details;
    }

    /** Just returns a project that wraps the serialized details from this model. */
    public LHProtos.Project getProto() {
        LHProtos.Project.Builder proto = LHProtos.Project.newBuilder();
        proto.setSerializedPaymentDetails(projectReq.toByteString());
        return proto.build();
    }

    public LHProtos.ProjectDetails getProtoDetails() {
        return projectReq;
    }

    /**
     * Returns a string that is used to uniquely identify a project: a 256-bit hash of the project contents.
     */
    public String getID() {
        return hash.toString();
    }

    /** Returns a human-readable title for the project. */
    public String getTitle() {
        return title;
    }

    /** Returns a human-readable description for the project. */
    public String getMemo() {
        return projectReq.getMemo();
    }

    /** Returns the total target value of the crowdfund in satoshis (i.e. sum of all contract outputs). */
    public Coin getGoalAmount() {
        return Coin.valueOf(goalAmount);
    }

    /** Returns a byte array containing a JPEG or PNG to use for the cover image. */
    public ByteString getCoverImage() {
        return projectReq.getExtraDetails().getCoverImage();
    }

    /** Returns a deep copy of the list of outputs. */
    public List<TransactionOutput> getOutputs() {
        return outputs.stream().map(TransactionOutput::duplicateDetached).collect(toList());
    }

    /** Returns expiry time in seconds since the epoch */
    public Instant getExpires() {
        return Instant.ofEpochSecond(projectReq.getExpires());
    }

    /** If true, do not allow any further pledges to be made to this project. */
    public boolean isExpired() {
        return now().isAfter(getExpires());
    }

    /** Returns the URL (if any) to which the pledge should be submitted, or null if none specified. */
    @Nullable
    public URI getPaymentURL() {
        if (url != null && "localhost".equals(url.getHost())) {
            // Switch port for easier local testing (vs the default of port 80).
            URI newUrl = unchecked(() -> new URI(String.format("http://%s:%d%s", url.getHost(), LHUtils.HTTP_LOCAL_TEST_PORT, url.getPath())));
            log.info("Switched URL to {}", newUrl);
            return newUrl;
        } else {
            return url;
        }
    }

    /**
     * Called to check a pledge message that was submitted by a pledger. The returned future completes once UTXO lookup
     * and script execution is done, or an error is encountered in which case the future will have the Ex subclass
     * representing the error.
     */
    public CompletableFuture<LHProtos.Pledge> verifyPledge(UTXOSource peer, LHProtos.Pledge pledge) {
        try {
            log.info("Checking pledge for project '{}' [{}]", getTitle(), getID());
            Transaction tx = fastSanityCheck(pledge);
            return lookupUTXOs(peer, tx).thenApply((result) -> {
                if (result.size() != tx.getInputs().size()) {
                    log.error("Could not locate all pledge UTXOs: may be double spent. Found:\n{}\n ... " +
                            " and pledge tx is...\n{}", result.toString(), tx);
                    throw new Ex.UnknownUTXO();
                }
                // The pledge matches some unspent outputs: now verify the scripts can spend and are signed with
                // SIGHASH_ANYONECANPAY as appropriate.
                verifyScripts(tx, result);
                // Check that the pledge.total_input_value field is consistent/correct, and is not under the min
                // pledge size for this project.
                verifyValues(pledge, result);
                // The pledge appears to be connected to unspent outputs and should be accepted by the network.
                // So we think it's a success!
                log.info("Pledge appears to be OK");
                return pledge;
            });
        } catch (Exception e) {
            CompletableFuture<LHProtos.Pledge> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    private void verifyValues(LHProtos.Pledge pledge, List<TransactionOutput> result) {
        // Verify that the pledge total_input_value field is consistent with the fetched UTXOs.
        long totalValue = 0;
        for (TransactionOutput output : result) totalValue += output.getValue().longValue();
        if (pledge.getPledgeDetails().getTotalInputValue() != totalValue || totalValue == 0)
            throw new Ex.CachedValueMismatch();
        if (totalValue < minPledgeAmount)
            throw new Ex.PledgeTooSmall(minPledgeAmount - totalValue);
    }

    private void verifyScripts(Transaction tx, List<TransactionOutput> result) throws VerificationException {
        // We assume that the ordering of the result list matches the tx input order.
        //
        // Add a random input that cannot be predicted by the pledgor, and run the script. If all the
        // signatures are SIGHASH_ANYONECANPAY and the input scripts satisfy the output scripts, the
        // random input should be ignored and verification should pass.
        //
        checkState(tx.getInputs().size() == result.size());
        tx = addRandomInput(tx);
        for (int i = 0; i < tx.getInputs().size() - 1; i++) {
            // Also check that the script we're about to run is of an expected form and contains a CHECKSIG. A remote
            // peer could have provided us with an OP_TRUE script or equivalent that would always pass here, even if
            // that's not the real script the tx was signed with. Forcing it to contain a CHECKSIG ensures that the
            // signature in the input is verified and that can't pass unless the scriptPubKey is as expected.
            // We also allow P2SH here because the input script must contain the correct output script in this case,
            // thus the provided output must match.
            final Script scriptPubKey = result.get(i).getScriptPubKey();
            if (isSafeToCrossCheck(scriptPubKey)) {
                TransactionInput input = tx.getInput(i);
                // Try to stop some idiot/troll from giving us a non-standard input, thus making us think we've raised
                // our funds but actually cannot easily claim the money.
                DefaultRiskAnalysis.RuleViolation violation = isInputStandard(input, scriptPubKey);
                if (violation != DefaultRiskAnalysis.RuleViolation.NONE) {
                    log.error("TX input {} is non-standard due to rule {}", violation);
                    throw new Ex.NonStandardInput();
                }
                try {
                    input.verify(result.get(i));
                } catch (VerificationException e) {
                    log.error("TX input {} failed with scriptSig {}    scriptPubKey {}", i, input.getScriptSig(), result.get(0).getScriptPubKey());
                    throw e;
                }
            } else
                throw new VerificationException("Unexpected script form returned by peer: " + scriptPubKey);
        }
    }

    // TODO: Move this into bitcoinj post-0.12
    private DefaultRiskAnalysis.RuleViolation isInputStandard(TransactionInput input, Script scriptPubKey) {
        DefaultRiskAnalysis.RuleViolation violation = input.isStandard();
        if (violation != DefaultRiskAnalysis.RuleViolation.NONE)
            return violation;
        Script scriptSig = input.getScriptSig();
        int args = getNumExpectedScriptSigArgs(scriptPubKey);
        LinkedList<byte[]> stack = Lists.newLinkedList();
        // This is fast and cannot execute any signature checks, because we already verified with the previous
        // isStandard() call that it only contains data pushes.
        Script.executeScript(null, -1, scriptSig, stack, true);
        if (scriptPubKey.isPayToScriptHash()) {
            Script redeemScript = null;
            try {
                if (stack.isEmpty())
                    throw new VerificationException("Empty stack");   // Should never happen: can't have empty scripts.
                redeemScript = new Script(stack.getLast());
                args += getNumExpectedScriptSigArgs(redeemScript);
            } catch (VerificationException e) {
                // We can get here if the redeem script is corrupted or unparseable in some way, or if the script type
                // simply isn't recognised by getNumExpected... - Bitcoin Core appears to simply treat any garbage
                // script as non-standard.
                //
                // Regardless, a non-recognised script is always treated as OK if it has 15 or fewer sigops, even if
                // it were to leave extra stuff on the stack.
                if (redeemScript != null && Script.getSigOpCount(stack.getLast()) <= Script.MAX_P2SH_SIGOPS)
                    return DefaultRiskAnalysis.RuleViolation.NONE;
            }
        }
        if (stack.size() != args)
            return DefaultRiskAnalysis.RuleViolation.NONEMPTY_STACK;
        return DefaultRiskAnalysis.RuleViolation.NONE;
    }

    private int getNumExpectedScriptSigArgs(Script scriptPubKey) {
        if (scriptPubKey.isSentToAddress())
            return 2;
        else if (scriptPubKey.isSentToRawPubKey())
            return 1;
        else if (scriptPubKey.isSentToMultiSig())
            return scriptPubKey.getChunks().get(0).decodeOpN();
        else if (scriptPubKey.isPayToScriptHash())
            return 1;   // Doesn't include the args needed by the script itself.
        else
            throw new VerificationException("Unknown scriptPubKey type");
    }

    private boolean isSafeToCrossCheck(Script scriptPubKey) {
        return scriptPubKey.isSentToRawPubKey() ||
               scriptPubKey.isSentToAddress() ||
               scriptPubKey.isSentToMultiSig() ||
               scriptPubKey.isPayToScriptHash();
    }

    private Transaction addRandomInput(Transaction tx) {
        tx = new Transaction(params, tx.bitcoinSerialize());
        byte[] rand = new byte[32];
        new SecureRandom().nextBytes(rand);
        tx.addInput(new TransactionInput(params, tx, new ScriptBuilder().data(rand).build().getProgram()));
        return tx;
    }

    private CompletableFuture<List<TransactionOutput>> lookupUTXOs(UTXOSource peer, Transaction tx) {
        List<TransactionOutPoint> outPoints = tx.getInputs().stream().map(TransactionInput::getOutpoint)
                .collect(toList());
        return peer.getUTXOs(outPoints);
    }

    /** Check that the pledge protobuf passes basic validity checks. */
    public Transaction fastSanityCheck(LHProtos.Pledge pledge) {
        if (pledge.getTransactionsList().isEmpty())
            throw new Ex.NoTransactionData();
        // We take the last the transaction because the others are dependencies.
        Transaction tx = LHUtils.pledgeToTx(params, pledge);
        if (tx.getOutputs().size() != outputs.size())
            throw new Ex.TxWrongNumberOfOutputs(tx.getOutputs().size(), outputs.size());
        // Output scripts must match project output scripts. We assume the project creator doesn't specify an invalid
        // or non-standard output: we could check for that later, however.
        for (int i = 0; i < tx.getOutputs().size(); i++) {
            if (!outputs.get(i).duplicateDetached().equals(tx.getOutput(i).duplicateDetached()))
                throw new Ex.OutputMismatch();
        }
        tx.verify();
        return tx;
    }

    /**
     * Returns a Transaction that combines the given pledges together. No fee is included.
     *
     * @throws lighthouse.protocol.Ex.ValueMismatch if the pledges don't total to an exact match for the goal.
     */
    public Transaction completeContract(Set<LHProtos.Pledge> pledges) {
        Transaction contract = new Transaction(params);
        outputs.forEach(contract::addOutput);
        long allPledgesValue = pledges.stream().mapToLong(pledge -> pledge.getPledgeDetails().getTotalInputValue()).sum();
        if (allPledgesValue != goalAmount)
            throw new Ex.ValueMismatch(allPledgesValue - goalAmount);
        pledges.stream().map(this::fastSanityCheck).forEach(pledge -> {
            pledge.getInputs().forEach(contract::addInput);
        });
        contract.setPurpose(Transaction.Purpose.ASSURANCE_CONTRACT_CLAIM);
        contract.verify();
        return contract;
    }

    /** Returns a future for the project status that completes when successfully downloaded via HTTP. */
    public CompletableFuture<LHProtos.ProjectStatus> getStatus(PledgingWallet wallet, @Nullable KeyParameter key) {
        final URI paymentURL = getPaymentURL();
        if (paymentURL == null)
            return null;
        CompletableFuture<LHProtos.ProjectStatus> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                final int TIMEOUT_MS = 10 * 1000;
                URLConnection connection = getServerQueryURL(wallet, key).openConnection();
                connection.setDoOutput(true);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.addRequestProperty("User-Agent", GET_STATUS_USER_AGENT);
                connection.connect();
                byte[] bits = Streams.readAllLimited(connection.getInputStream(), 1024 * 1024);  // 1mb limit.
                future.complete(LHProtos.ProjectStatus.parseFrom(bits));
            } catch (Exception e) {
                if (e instanceof FileNotFoundException) {
                    log.warn("Project not yet on the server: 404 Not Found: {}", paymentURL);
                } else {
                    log.error("Failed download from server " + paymentURL, e);
                }
                future.completeExceptionally(Throwables.getRootCause(e));
            }
        }, "Project downloader");
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    private URL getServerQueryURL(PledgingWallet wallet, @Nullable KeyParameter key) {
        // It's ludicrous that Java has two URI/URL classes and both of them suck. How hard can this be, people?!
        URI uri = checkNotNull(getPaymentURL(), "Not a server assisted project");
        String path = uri.getPath();
        // The actual message used doesn't really matter as we simply are using signatures like a password here.
        // Communication with the server absolutely should be protected by SSL, in which case we have confidentiality
        // so replay attacks aren't a concern.
        String msg = getID();
        String rawSig = signAsOwner(wallet, msg, key);
        if (rawSig == null)
            return unchecked(uri::toURL);
        String signature = unchecked(() -> URLEncoder.encode(rawSig, "UTF-8"));
        if (path.contains("?"))
            path = path + "&msg=" + msg + "&sig=" + signature;
        else
            path = path + "?msg=" + msg + "&sig=" + signature;
        String fpath = path;
        return unchecked(() -> new URL(uri.getScheme(), uri.getHost(), uri.getPort(), fpath));
    }

    @Override
    public String toString() {
        return String.format("%s [goal: %s]", getTitle(), getGoalAmount());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Project project = (Project) o;

        if (!projectReq.equals(project.projectReq)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return projectReq.hashCode();
    }

    public String getSuggestedFileName() {
        return getSuggestedFileName(getTitle());
    }

    public static String getSuggestedFileName(String title) {
        return LHUtils.titleToUrlString(title) + DiskManager.PROJECT_FILE_EXTENSION;
    }

    public Coin getMinPledgeAmount() {
        return getMinPledgeAmountFrom(minPledgeAmount);
    }

    /**
     * Returns the max of the min pledge amount recorded in the project definition, and the min output value times two.
     * The reason is: we need to be able to revoke a pledge we made, and that may require paying a fee. If we allowed
     * a pledge of the dust amount, we'd be unable to revoke because the entire amount we're trying to revoke would
     * get consumed in fees.
     */
    public static Coin getMinPledgeAmountFrom(long minPledgeAmount) {
        return Coin.valueOf(Math.max(minPledgeAmount, Transaction.REFERENCE_DEFAULT_MIN_TX_FEE.multiply(4).value));
    }

    @Nullable
    public String signAsOwner(PledgingWallet wallet, String message, @Nullable KeyParameter aesKey) {
        DeterministicKey realKey = wallet.getAuthKeyFromIndexOrPubKey(authKey, authKeyIndex);
        if (realKey == null || (aesKey == null && realKey.isEncrypted()))
            return null;
        return realKey.signMessage(message, aesKey);
    }

    public void authenticateOwner(String message, String signatureBase64) throws SignatureException {
        ECKey.fromPublicOnly(authKey).verifyMessage(message, signatureBase64);
    }

    public byte[] getAuthKey() {
        return authKey;
    }

    public int getAuthKeyIndex() {
        return authKeyIndex;
    }

    public NetworkParameters getParams() {
        return params;
    }
}
