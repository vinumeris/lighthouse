package lighthouse

import com.google.common.base.Preconditions.checkArgument
import com.google.common.base.Preconditions.checkState
import com.google.common.base.Splitter
import com.google.common.base.Throwables.getRootCause
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import javafx.beans.InvalidationListener
import javafx.beans.property.LongProperty
import javafx.beans.property.SimpleLongProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.collections.ObservableMap
import javafx.collections.ObservableSet
import lighthouse.files.AppDirectory
import lighthouse.protocol.*
import lighthouse.protocol.LHUtils.futureOfFutures
import lighthouse.protocol.LHUtils.hashFromPledge
import lighthouse.protocol.LHUtils.pledgeToTx
import lighthouse.threading.AffinityExecutor
import lighthouse.threading.ObservableMirrors
import lighthouse.utils.asCoin
import lighthouse.utils.hash
import lighthouse.utils.plus
import lighthouse.utils.projectID
import net.jcip.annotations.GuardedBy
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.async
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.jvm.asDispatcher
import org.bitcoinj.core.*
import org.bitcoinj.core.listeners.AbstractBlockChainListener
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener
import org.bitcoinj.core.listeners.WalletCoinEventListener
import org.bitcoinj.params.RegTestParams
import org.slf4j.LoggerFactory
import org.spongycastle.crypto.params.KeyParameter
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.BiConsumer
import java.util.function.BiFunction

/**
 * LighthouseBackend is a bit actor-ish: it uses its own thread which owns almost all internal state. Other
 * objects it owns can sometimes use their own threads, but results are always marshalled onto the LighthouseBackend
 * thread before the Observable collections are modified. This design assists with avoiding locking and keeping
 * concurrency manageable. A prior approach based on ordinary threading and locking got too complicated.

 * LighthouseBackend is used in both the GUI app and on the server. In the server case the wallet will typically be
 * empty and projects/pledges are stored on disk only. Ideally, it's connected to a local Bitcoin Core node.
 */
public class LighthouseBackend public constructor(
        public val mode: LighthouseBackend.Mode,
        public val params: NetworkParameters,
        private val bitcoin: IBitcoinBackend,
        public val executor: AffinityExecutor.ServiceAffinityExecutor
) : AbstractBlockChainListener() {
    companion object {
        public val PROJECT_STATUS_FILENAME: String = "project-status.txt"
        public val PROJECT_FILE_EXTENSION: String = ".lighthouse-project"
        public val PLEDGE_FILE_EXTENSION: String = ".lighthouse-pledge"

        private val log = LoggerFactory.getLogger(LighthouseBackend::class.java)

        /** Returns a property calculated from the given list, with no special mirroring setup.  */
        public fun bindTotalPledgedProperty(pledges: ObservableSet<LHProtos.Pledge>): LongProperty {
            // We must ensure that the returned property keeps a strong reference to pledges, in case it's the only one.
            return object : SimpleLongProperty(0) {
                private val pledgesRef = pledges

                // This should probably be done differently (as a lazy binding?) but I doubt it matters.
                init {
                    pledgesRef.addListener(InvalidationListener { update() })
                    update()
                }

                private fun update() {
                    set(pledgesRef.fold(0L, { left, right -> left + right.pledgeDetails.totalInputValue }))
                }
            }
        }

        private val BLOCK_PROPAGATION_TIME_SECS = 30    // 90th percentile block propagation times ~15 secs
    }

    /**
     * Is the project currently open for pledges or did it complete successfully? In future we might have EXPIRED here
     * too for deadlined contracts.
     */
    public enum class ProjectState {
        OPEN,
        ERROR,
        CLAIMED,

        UNKNOWN  // Used only for offline mode
    }
    public data class ProjectStateInfo(public val state: ProjectState, public val claimedBy: Sha256Hash?)

    private val initialized = CompletableFuture<Boolean>()
    private var minPeersForUTXOQuery = 2

    public enum class Mode {
        CLIENT,
        SERVER
    }

    public class CheckStatus private constructor(public val inProgress: Boolean, public val error: Throwable?) {
        override fun toString() = "CheckStatus{inProgress=$inProgress, error=$error}"

        companion object {
            public fun inProgress(): CheckStatus = CheckStatus(true, null)
            public fun withError(error: Throwable): CheckStatus = CheckStatus(false, error)
        }
    }
    private val checkStatuses = FXCollections.observableHashMap<Project, CheckStatus>()

    // Non-revoked pledges either:
    //  - Fetched from the remote server, which is inherently trusted as it's run by the person you're
    //    trying to give money to (or is trusted by the person you're trying to give money to)
    //  - Checked against the P2P network, which is only semi-trusted but in practice should
    //    work well enough to just keep our UI consistent, which is all we use it for.
    //  - From the users wallet, which are trusted because we created it.
    private val pledges = hashMapOf<Project, ObservableSet<LHProtos.Pledge>>()

    @GuardedBy("this")
    private val projectsByUrlPath = hashMapOf<String, Project>()

    private val projects = FXCollections.observableArrayList<Project>()
    private val projectsMap: MutableMap<Sha256Hash, Project> = linkedMapOf()
    private val projectStates: ObservableMap<Sha256Hash, ProjectStateInfo> = FXCollections.observableMap(linkedMapOf())
    private val kctx = Kovenant.createContext { workerContext.dispatcher = executor.asDispatcher() }

    init {
        if (params === RegTestParams.get()) {
            setMinPeersForUTXOQuery(1)
            setMaxJitterSeconds(1)
        }

        val startupTime = System.nanoTime()

        // This is here just to satisfy thread affinity asserts.
        executor.fetchFrom {
            try {
                loadData()

                with(bitcoin) {
                    // Load pledges found in the wallet.
                    for (pledge in wallet.pledges) {
                        val project = projectsMap[pledge.projectID]
                        if (project != null)
                            getPledgesFor(project).add(pledge)
                        else
                            log.error("Found a pledge in the wallet but could not find the corresponding project: {}", pledge.projectID)
                    }

                    wallet.addOnPledgeHandler(executor) { project, pledge -> getPledgesFor(project).add(pledge) }
                    wallet.addOnRevokeHandler(executor) { pledge ->
                        val project = projectsMap[pledge.projectID]
                        if (project != null) {
                            getPledgesFor(project).remove(pledge)
                        } else {
                            log.error("Found a pledge in the wallet but could not find the corresponding project: {}", pledge.projectID)
                        }
                    }
                    // Make sure we can spot projects being claimed.
                    wallet.addCoinEventListener(executor, object : WalletCoinEventListener {
                        override fun onCoinsSent(wallet: Wallet, tx: Transaction, prevBalance: Coin, newBalance: Coin) {
                        }

                        override fun onCoinsReceived(wallet: Wallet, tx: Transaction, prevBalance: Coin, newBalance: Coin) {
                            checkPossibleClaimTX(tx)
                        }

                    })

                    for (tx in wallet.getTransactions(false)) {
                        if (tx.outputs.size() != 1) continue    // Optimization: short-circuit: not a claim.
                        val project = getProjectFromClaim(tx) ?: continue
                        log.info("Loading stored claim ${tx.hash} for $project")
                        addClaimConfidenceListener(executor, tx, project)
                    }

                    // Let us find revocations by using a direct Bloom filter provider. We could watch out for claims in this
                    // way too, but we want the wallet to monitor confidence of claims, and don't care about revocations as much.
                    installBloomFilterProvider()
                    chain.addListener(this@LighthouseBackend, executor)
                }

                // And signal success.
                log.info("Backend initialized in ${(System.nanoTime() - startupTime) / 1000000} msec...")
                initialized.complete(true)

                // Checking of project statuses against the network will be kicked off in the start method, called
                // after we hauled the UI onto the screen.
            } catch (e: Throwable) {
                log.error("Backend init failed", e)
                initialized.completeExceptionally(e)
            }
        }
    }

    public fun start() {
        projectsMap.values().forEach { checkProject(it) }
    }

    private fun checkProject(project: Project) {
        // Don't attempt to check the server when we ARE the server!
        if (project.isServerAssisted && mode === Mode.CLIENT) {
            log.info("Load: checking project against server: $project")
            refreshProjectStatusFromServer(project)
        } else {
            log.info("Load: checking project against P2P network: $project")
            val wallet = bitcoin.wallet
            val pledges = getPledgesFor(project).filterNot { wallet.pledges.contains(it) || wallet.wasPledgeRevoked(it) }.toSet()
            checkPledgesAgainstP2PNetwork(project, pledges)
        }
    }

    private fun loadData() {
        val appdir = AppDirectory.dir().toFile()
        // Load the project files. We do not know yet what order they appear on screen, so we cannot
        // optimise by loading the ones the user would see first! This should be fixed in a future version.
        // However, we could load them in parallel to speed things up.
        loadProjectFiles(appdir)

        // Load the "statuses file". This gives us the basic data we need to render the UI and preserve a consistent
        // ordering across restarts regardless of what the filesystem gives us.
        val path = AppDirectory.dir().resolve(PROJECT_STATUS_FILENAME)
        if (Files.exists(path))
            loadProjectStatuses(path)
        projectStates.addListener(InvalidationListener { saveProjectStatuses(path) })

        // Load the pledges and match them up to known projects.
        loadPledges(appdir)
    }

    private fun loadProjectFiles(appdir: File) {
        appdir.listFiles {
            it.toString() endsWith PROJECT_FILE_EXTENSION && it.isFile
        }?.map {
            tryLoadProject(it)
        }?.filterNotNull()?.forEach { project ->
            insertProject(project)
        }
    }

    private fun insertProject(project: Project) {
        executor.checkOnThread()
        projects.add(project)
        // Assume new projects are open: we have no other way to tell for now: would require a block explorer
        // lookup to detect that the project came and went already. But we do remember even if the project
        // is deleted and came back.
        projectStates[project.hash] = ProjectStateInfo(ProjectState.OPEN, null)
        // Ensure we can look up a Project when we receive an HTTP request.
        if (project.isServerAssisted && mode == Mode.SERVER)
            projectsByUrlPath[project.paymentURL.path] = project
        projectsMap[project.hash] = project
        configureWalletToSpotClaimsFor(project)
    }

    private fun saveProjectStatuses(path: Path) {
        executor.executeASAP {
            log.info("Saving project statuses")
            val lines = arrayListOf<String>()
            for (entry in projectStates.entrySet()) {
                val v = if (entry.getValue().state == ProjectState.OPEN) "OPEN" else entry.getValue().claimedBy.toString()
                lines.add("${entry.getKey()}=$v")
            }
            Files.write(path, lines)
        }
    }

    private fun loadProjectStatuses(path: Path) {
        // Parse the file that contains "project_hash = OPEN | claimed_by_hash" pairs.
        // This file is a hack left over from the mad rush to beta. We should replace it with a mini SQLite or
        // something similar.
        path.toFile().forEachLine { line ->
            if (!line.startsWith("#")) {
                val parts = Splitter.on("=").splitToList(line)
                val key = Sha256Hash.wrap(parts[0])
                val value = parts[1]
                if (value == "OPEN") {
                    projectStates[key] = ProjectStateInfo(ProjectState.OPEN, null)
                } else {
                    val claimedBy = Sha256Hash.wrap(value)
                    projectStates[key] = ProjectStateInfo(ProjectState.CLAIMED, claimedBy)
                    log.info("Project $key is marked as claimed by $claimedBy")
                }
            }
        }
    }

    private fun loadPledges(appdir: File) {
        appdir.listFiles {
            it.toString() endsWith PLEDGE_FILE_EXTENSION
        }?.map {
            tryLoadPledge(it)
        }?.filterNotNull()?.forEach {
            val p = projectsMap[it.projectID]
            if (p == null) {
                log.warn("Found pledge we don't have the project for: ${it.hash}")
            } else {
                getPledgesFor(p).add(it)
            }
        }
    }

    private fun tryLoadPledge(it: File, ignoreErrors: Boolean = true): LHProtos.Pledge? = try {
        it.inputStream().use { LHProtos.Pledge.parseFrom(it) }
    } catch(e: Exception) {
        log.error("Failed to load pledge from $it")
        if (ignoreErrors) null else throw e
    }

    private fun tryLoadProject(it: File, ignoreErrors: Boolean = true): Project? = try {
        val project = it.inputStream().use { stream -> Project(LHProtos.Project.parseFrom(stream)) }
        if (project.params == params) {
            project
        } else {
            log.warn("Ignoring project with mismatched params: $it")
            null
        }
    } catch (e: Exception) {
        log.error("Project file $it could not be read, ignoring: ${e.getMessage()}")
        if (ignoreErrors)
            null
        else
            throw e
    }

    private fun addClaimConfidenceListener(executor: AffinityExecutor, transaction: Transaction, project: Project) {
        transaction.confidence.addEventListener(executor, object : TransactionConfidence.Listener {
            var done = false
            override fun onConfidenceChanged(conf: TransactionConfidence, reason: TransactionConfidence.Listener.ChangeReason) {
                if (!done && checkClaimConfidence(transaction, conf, project)) {
                    // Because an async thread is queuing up events on our thread, we can still get run even after
                    // the event listener has been removed. So just quiet things a bit here.
                    done = true
                    transaction.confidence.removeEventListener(this)
                }
            }
        })
    }

    private fun checkPossibleClaimTX(tx: Transaction) {
        // tx may or may not be a transaction that completes a project we're aware of. We can never really know for
        // sure because of how the Bitcoin protocol works, but we check here to see if the outputs all match the
        // project and if so, we presume that it's completed. Note that 'tx' here comes from the network and might
        // be unconfirmed or unconfirmable at this point, however, we want to update the UI as soon as the claim is
        // seen so the user sees what they expect to see: we can show a confirmations ticker on the screen at the UI
        // level, or the user can just use whatever the destination wallet is to find out when they've got the money
        // to a high enough degree of confidence.
        executor.checkOnThread()
        val project = getProjectFromClaim(tx) ?: return
        log.info("Found claim tx {} with {} inputs for project {}", tx.hash, tx.inputs.size(), project)
        tx.verify()   // Already done but these checks are fast, can't hurt to repeat.
        // We could check that the inputs are all (but one) signed with SIGHASH_ANYONECANPAY here, but it seems
        // overly strict at the moment.
        tx.purpose = Transaction.Purpose.ASSURANCE_CONTRACT_CLAIM
        // Figure out if the claim is good enough to tell the user about yet. Note that our confidence can go DOWN
        // as well as up if the transaction is double spent or there's a re-org that sends it back to being pending.
        checkClaimConfidence(tx, tx.confidence, project)
        addClaimConfidenceListener(executor, tx, project)
    }

    private fun checkClaimConfidence(transaction: Transaction, conf: TransactionConfidence, project: Project): Boolean {
        executor.checkOnThread()
        val type = conf.confidenceType!!
        if (type == TransactionConfidence.ConfidenceType.PENDING) {
            val seenBy = conf.numBroadcastPeers()
            // This logic taken from bitcoinj's TransactionBroadcast class.
            val numConnected = bitcoin.peers.connectedPeers.size()
            val numToBroadcastTo = Math.max(1, Math.round(Math.ceil(numConnected / 2.0))).toInt()
            val numWaitingFor = Math.ceil((numConnected - numToBroadcastTo) / 2.0).toInt()

            log.info("Claim seen by {}/{} peers", seenBy, numWaitingFor)
            if (seenBy < numWaitingFor)
                return false
        }

        if (type == TransactionConfidence.ConfidenceType.PENDING || type == TransactionConfidence.ConfidenceType.BUILDING) {
            if (conf.depthInBlocks > 3)
                return true  // Don't care about watching this anymore.
            if (projectStates[project.hash]!!.state !== ProjectState.CLAIMED) {
                log.info("Claim propagated or mined")
                projectStates[project.hash] = ProjectStateInfo(ProjectState.CLAIMED, transaction.hash)
            }
            return false
        }

        if (type == TransactionConfidence.ConfidenceType.DEAD) {
            log.warn("Claim double spent! Overridden by {}", conf.overridingTransaction)
            projectStates[project.hash] = ProjectStateInfo(ProjectState.ERROR, null)
        }

        return false  // Don't remove listener.
    }

    public fun getProjectFromClaim(claim: Transaction): Project? {
        executor.checkOnThread()
        return projectsMap.values().firstOrNull { project -> LHUtils.compareOutputsStructurally(claim, project) }
    }

    public fun waitForInit() {
        initialized.get()
    }

    private fun configureWalletToSpotClaimsFor(project: Project) {
        // Make sure we keep an eye on the project output scripts so we can spot claim transactions, note
        // that this works even if we never make any pledge ourselves, for example because we are a server.
        // We ask the wallet to track it instead of doing this ourselves because the wallet knows how to do
        // things like watch out for double spends and track chain depth.
        val scripts = project.outputs.map { it.scriptPubKey }
        scripts.forEach { it.creationTimeSeconds = project.protoDetails.time }
        val toAdd = scripts.toArrayList()

        toAdd.removeAll(bitcoin.wallet.watchedScripts)
        if (toAdd.isNotEmpty())
            bitcoin.wallet.addWatchedScripts(toAdd)
    }

    private fun isPledgeKnown(pledge: LHProtos.Pledge): Boolean {
        executor.checkOnThread()
        pledges.values().forEach { if (it.contains(pledge)) return true }
        return false
    }

    /**
     * This method does a check of all current pledges + the given pledge. If it's found to be good, it'll be added
     * to our open pledges list ... or not. This is used when a pledge is found on disk or submitted to the server.
     */
    private fun checkPledgeAgainstP2PNetwork(project: Project, pledge: LHProtos.Pledge): CompletableFuture<Set<LHProtos.Pledge>> {
        executor.checkOnThread()
        val both = HashSet(getPledgesFor(project))
        both.add(pledge)
        return checkPledgesAgainstP2PNetwork(project, both)
    }

    // Completes with the set of pledges that passed verification.
    // If checkingAllPledges is false then pledges contains a single item, otherwise it contains all pledges for the
    // project together.
    private fun checkPledgesAgainstP2PNetwork(project: Project, pledges: Set<LHProtos.Pledge>): CompletableFuture<Set<LHProtos.Pledge>> {
        if (pledges.isEmpty()) {
            log.info("No pledges to check")
            return CompletableFuture.completedFuture<Set<LHProtos.Pledge>>(emptySet())
        }
        val result = CompletableFuture<Set<LHProtos.Pledge>>()
        if (mode === Mode.CLIENT) {
            // If we're running inside the desktop app, forbid pledges with dependencies for now. It simplifies things:
            // the app is supposed to broadcast and allow a dependency tx to settle before creating a pledge file, so
            // we should never hit the exceptional case below when the protocol is being followed. We could call
            // broadcastDependenciesOf() instead when we first become aware of the pledge if we wanted to change this
            // in future.
            for (pledge in pledges) {
                if (pledge.transactionsCount != 1)
                    result.completeExceptionally(Ex.TooManyDependencies(pledge.transactionsCount))
            }
        }
        executor.executeASAP {
            val state = projectStates[project.hash]
            if (state?.state == ProjectState.CLAIMED && (!project.isServerAssisted || mode == Mode.SERVER)) {
                // Serverless project or we are the server.
                val contract = bitcoin.wallet.getTransaction(state!!.claimedBy)  // KT-7290
                if (contract == null) {
                    log.error("Serverless project marked as claimed but contract not found in wallet! Assuming all pledges were taken.")
                    syncPledges(project, pledges, ArrayList(pledges))
                    result.complete(pledges)
                } else {
                    log.info("Project '${project.title}' is claimed by ${contract.hash}, skipping network check as all pledges would be taken")
                    val appearedInClaim = pledges.filter { LHUtils.pledgeAppearsInClaim(project, it, contract) }
                    syncPledges(project, pledges, appearedInClaim)
                    result.complete(HashSet(appearedInClaim))
                }
            } else {
                log.info("Checking ${pledges.size()} pledge(s) against P2P network for '$project'")
                markAsInProgress(project)
                val peerFuture = bitcoin.xtPeers.waitForPeersOfVersion(minPeersForUTXOQuery, GetUTXOsMessage.MIN_PROTOCOL_VERSION.toLong())
                if (!peerFuture.isDone) {
                    log.info("Waiting to find {} peers that support getutxo", minPeersForUTXOQuery)
                    for (peer in bitcoin.xtPeers.connectedPeers) log.info("Connected to: {}", peer)
                }
                Futures.addCallback(peerFuture, object : FutureCallback<List<Peer>> {
                    override fun onSuccess(allPeers: List<Peer>) {
                        log.info("Peers available: {}", allPeers)
                        executor.checkOnThread()
                        // Do a fast delete of any peers that claim they don't support NODE_GETUTXOS. We ensure we always
                        // find nodes that support it elsewhere.
                        val origSize = allPeers.size()
                        val xtPeers = allPeers.filter { it.peerVersionMessage.isGetUTXOsSupported }
                        if (xtPeers.isEmpty()) {
                            val ex = Exception("No nodes of high enough version advertised NODE_GETUTXOS")
                            log.error(ex.getMessage())
                            checkStatuses.put(project, CheckStatus.withError(ex))
                            result.completeExceptionally(ex)
                        } else {
                            if (xtPeers.size() != origSize)
                                log.info("Dropped {} peers for not supporting NODE_GETUTXOS, now have {}", xtPeers.size() - origSize, xtPeers.size())
                            doUTXOLookupsForPledges(project, pledges, xtPeers, result)
                        }
                    }

                    override fun onFailure(t: Throwable) {
                        // This should actually never happen as the peer future cannot fail.
                        log.error("Failed to locate peers", t)
                        markAsErrored(project, t)
                        result.completeExceptionally(t)
                    }
                }, executor)
            }
        }
        return result
    }

    private fun markAsInProgress(project: Project) {
        log.info("Checking in progress: {}", project)
        checkStatuses.put(project, CheckStatus.inProgress())
    }

    private fun markAsErrored(project: Project, ex: Throwable) {
        log.info("Checking had an error: {}", project)
        checkStatuses.put(project, CheckStatus.withError(getRootCause(ex)))
    }

    private fun markAsCheckDone(project: Project) {
        log.info("Checking done: {}", project)
        checkStatuses.remove(project)
    }

    private fun doUTXOLookupsForPledges(project: Project, pledges: Set<LHProtos.Pledge>, peers: List<Peer>, result: CompletableFuture<Set<LHProtos.Pledge>>) {
        executor.checkOnThread()
        try {
            // The multiplexor issues the same query to multiple peers and verifies they're all consistent.
            log.info("Querying {} peers", peers.size())
            val multiplexor = PeerUTXOMultiplexor(peers)
            // The batcher queues up queries from project.verifyPledge and combines them into a single query, to
            // speed things up and minimise network traffic.
            val utxoSource = BatchingUTXOSource(multiplexor)
            val pledgesInFixedOrder = ArrayList(pledges)
            val futures = pledgesInFixedOrder.map { project.verifyPledge(utxoSource, it) }
            try {
                utxoSource.run()   // Actually send the query.
                futureOfFutures(futures).get(10, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                // Some peer(s) didn't get back to us fast enough, they'll be filtered out below.
            }

            val allOutpoints = HashSet<TransactionOutPoint>()
            val verifiedPledges = ArrayList<LHProtos.Pledge>(futures.size())
            for (pair in futures zip pledgesInFixedOrder) {
                val future = pair.first
                if (!future.isDone) {
                    log.warn("getutxo lookup failed or timed out: {}", future)
                    continue
                }
                try {
                    val pledge = future.get()
                    // Check that this pledge is not spending the same outputs as any other accepted pledge.
                    // Note that project.verifyPledge already called fastSanityCheck -> tx.verify() which verified
                    // the pledge tx does not itself have the same outpoints repeated in the same transaction/pledge.
                    // If it did then we exit on the above line and end up in the ExecutionException branches.
                    val tx = project.fastSanityCheck(pledge)
                    for (input in tx.inputs) {
                        if (allOutpoints.contains(input.outpoint))
                            throw ExecutionException(VerificationException.DuplicatedOutPoint())
                        allOutpoints.add(input.outpoint)
                    }
                    verifiedPledges.add(pledge)
                } catch (e: ExecutionException) {
                    // We ignore pledges if they are revoked, and throw a check error otherwise.
                    val cause = getRootCause(e)
                    if (cause !is Ex.UnknownUTXO) {
                        val pledge = pair.second
                        log.error("Pledge has an error, putting project in error state: {}: {}\n", LHUtils.hashFromPledge(pledge), pledge)
                        throw e
                    }
                }
            }
            log.info("{} of {} pledges verified (were not revoked/claimed)", verifiedPledges.size(), pledges.size())
            syncPledges(project, pledges, verifiedPledges)
            refreshBloomFilter()
            markAsCheckDone(project)
            result.complete(HashSet(verifiedPledges))
        } catch (e: InterruptedException) {
            log.error("Unexpected error checking pledge, marking project as in error state", e)
            markAsErrored(project, e)
            result.completeExceptionally(e)
        } catch (e: ExecutionException) {
            log.error("Unexpected error checking pledge, marking project as in error state", e)
            markAsErrored(project, e)
            result.completeExceptionally(e)
        }

    }

    /** Invokes a manual refresh by going back to the server. Can be called from any thread.  */
    @JvmOverloads public fun refreshProjectStatusFromServer(project: Project, aesKey: KeyParameter? = null): CompletableFuture<LHProtos.ProjectStatus> {
        // Sigh, wish Java had proper co-routines (there's a lib that does it nicely but is overkill for this function).
        // This is messy because we want to overlap multiple lookups and thenAcceptAsync doesn't work how you'd think
        // it works (it will block the backend thread whilst waiting for the getStatus call to complete).
        checkState(mode === Mode.CLIENT)
        val future = CompletableFuture<LHProtos.ProjectStatus>()
        executor.execute {
            markAsInProgress(project)
            project.getStatus(bitcoin.wallet, aesKey).whenCompleteAsync(BiConsumer { status, ex ->
                if (ex != null) {
                    markAsErrored(project, ex)
                    future.completeExceptionally(ex)
                } else {
                    try {
                        log.info("Processing project status")
                        executor.checkOnThread()
                        // Server's view of the truth overrides our own for UI purposes, as we might have failed to
                        // observe the contract/claim tx if the user imported the project post-claim.
                        //
                        // WARNING! During app startup we can end up with the p2p network racing with the server to
                        // tell us that the project was claimed. This is inherent - we're catching up with the block
                        // chain and are about to see the claim, whilst simultaneously we're asking the server for
                        // the status (because we don't want to wait for the block chain to sync before showing the
                        // user existing pledges). The code paths are slightly different because when we see the claim
                        // tx from the p2p network we only mark the pledges that appear in that tx as claimed, whereas
                        // here we mark all of them. This is due to the difference between serverless and server assisted
                        // projects (serverless can have open pledges even after a claim).
                        if (status.hasClaimedBy()) {
                            if (projectStates[project]?.state !== ProjectState.CLAIMED)
                                projectStates[project.hash] = ProjectStateInfo(ProjectState.CLAIMED, Sha256Hash.wrap(status.claimedBy.toByteArray()))
                            log.info("Project is claimed ({} pledges)", getPledgesFor(project).size())
                        }
                        // Status contains a new list of pledges. We should update our own observable list by touching it
                        // as little as possible. This ensures that as updates flow through to the UI any animations look
                        // good (as opposed to total replacement which would animate poorly).
                        syncPledges(project, HashSet(status.pledgesList), status.pledgesList)
                        markAsCheckDone(project)
                        future.complete(status)
                        log.info("Processing of project status from server complete")
                    } catch (t: Throwable) {
                        log.error("Caught exception whilst processing status", t)
                        future.completeExceptionally(t)
                    }

                }
            }, executor)
        }
        return future
    }

    private fun syncPledges(forProject: Project, testedPledges: Set<LHProtos.Pledge>, verifiedPledges: List<LHProtos.Pledge>) {
        // TODO: This whole function is too complicated and fragile. It should probably be split up for different server
        // vs client vs serverless codepaths.

        executor.checkOnThread()
        val curOpenPledges = getPledgesFor(forProject)

        // Build a map of pledgehash->pledge so we can dedupe server-scrubbed pledges.
        val hashes = curOpenPledges.map { LHUtils.hashFromPledge(it) to it }.toMap()

        // Try and update pledges with minimal touching, so animations work right.

        var newlyOpen = HashSet(verifiedPledges)
        newlyOpen.removeAll(curOpenPledges)
        if (mode === Mode.CLIENT) {
            // Servers should of course ideally not give us revoked pledges, but it may take a bit of time for the
            // server to notice. So there can be a window of time in which we know we revoked our own pledge, but the
            // server keeps sending it to us.
            //
            // Also remove if this is a scrubbed version of a pledge we already have i.e. because we created it, uploaded it
            // and are now seeing it come back to us.
            newlyOpen = newlyOpen.filterNot { bitcoin.wallet.wasPledgeRevoked(it) || (it.pledgeDetails.hasOrigHash() && hashes contains hashFromPledge(it)) }.toHashSet()
        }
        curOpenPledges.addAll(newlyOpen)
        val newlyInvalid = HashSet(testedPledges)
        newlyInvalid.removeAll(verifiedPledges)
        curOpenPledges.removeAll(newlyInvalid)
        if (forProject.paymentURL != null && mode === Mode.CLIENT) {
            // Little hack here. In the app when checking a server-assisted project we don't have the same notion of
            // "testedness" so testedPledges always equals verifiedPledges. So, we must remove revoked pledges here
            // manually. A better version in future would record stored server statuses to disk so we can always
            // compare against the previous state like we do in the serverless case, then, this would let us unify
            // the code paths, and it would give us better offline support too.
            //
            // TODO: Save server statuses to disk so we can render them offline and so tested vs verified pledges is meaningful.

            // Figure out which pledges are no longer being reported, taking into account scrubbing.
            val removedItems = HashSet(hashes.values())
            for (pledge in verifiedPledges) {
                val orig = hashes.get(hashFromPledge(pledge))
                if (orig != null)
                    removedItems.remove(orig)
            }
            if (removedItems.size() > 0) {
                log.info("Server no longer reporting some pledges, revoked: {}", removedItems)
                curOpenPledges.removeAll(removedItems)
            }
        }
    }

    /** Returns a new read-only list that has changes applied using the given executor.  */
    public fun mirrorProjects(executor: AffinityExecutor): ObservableList<Project> = executor.fetchFrom { ObservableMirrors.mirrorList(projects, executor) }

    @Throws(IOException::class)
    public fun saveProject(project: Project): Project {
        val filename = project.suggestedFileName
        val path = AppDirectory.dir().resolve(filename).toFile()
        log.info("Saving project to $path")
        path.writeBytes(project.proto.toByteArray())
        executor.execute {
            insertProject(project)
        }
        return project
    }

    public fun editProject(new: Project, old: Project): Project {
        if (new.hash == old.hash)
            return new

        // Overwrite the original file in our app dir.
        val file = AppDirectory.dir().resolve(old.suggestedFileName).toFile()
        log.info("Overwriting project at $file")
        file.writeBytes(new.proto.toByteArray())
        executor.execute {
            projectStates[new.hash] = projectStates[old.hash]
            // Leave the old project state in the table for now: it simplifies the UI update process.
            if (mode == Mode.SERVER) {
                // Ensure we can look up a Project when we receive an HTTP request.
                if (old.isServerAssisted)
                    projectsByUrlPath.remove(old.paymentURL.path)
                if (new.isServerAssisted)
                    projectsByUrlPath[new.paymentURL.path] = new
            }
            projectsMap.remove(old.hash)
            projectsMap[new.hash] = new
            val scripts = old.outputs.map { it.scriptPubKey }
            scripts.forEach { it.creationTimeSeconds = old.protoDetails.time }
            bitcoin.wallet.removeWatchedScripts(scripts)
            configureWalletToSpotClaimsFor(new)
            projects[projects.indexOf(old)] = new
        }
        return new
    }

    @Throws(IOException::class)
    public fun importProjectFrom(path: Path) {
        // Try loading the project first to ensure it's valid, throws exception if not.
        val project = tryLoadProject(path.toFile(), ignoreErrors = false)!!
        val destPath = AppDirectory.dir().resolve(path.fileName)
        val destFile = destPath.toFile()
        // path can equal destFile when we're in server mode.
        if (path.toAbsolutePath() != destPath.toAbsolutePath()) {
            if (destFile.exists()) {
                val theirHash = Sha256Hash.of(path.toFile())
                val ourHash = Sha256Hash.of(destFile)
                if (theirHash == ourHash) {
                    log.info("Attempted import of a project we already have, skipping")
                    return
                }
            }
            path.toFile().copyTo(destFile)
        }
        executor.execute {
            insertProject(project)
            checkProject(project)
        }
    }

    /**
     * Copies the given pledge path into the app directory, checks it, and loads it. Returns a future for when the
     * pledge check is complete. Meant to be used from the GUI only.
     */
    public fun importPledgeFrom(path: Path): Promise<LHProtos.Pledge, Throwable> {
        val deferred = deferred<LHProtos.Pledge, Throwable>()

        async(kctx) {
            val pledge = tryLoadPledge(path.toFile(), ignoreErrors = false)!!
            if (isPledgeKnown(pledge))
                return@async pledge
            val project = projectsMap[pledge.projectID] ?: throw Ex.UnknownProject()

            check(!project.isServerAssisted && mode == Mode.CLIENT)

            val destFile = AppDirectory.dir().resolve("${pledge.hash}${PLEDGE_FILE_EXTENSION}").toFile();
            if (destFile.exists())
                return@async pledge

            // TODO: Make this call use Kovenant too.
            checkPledgeAgainstP2PNetwork(project, pledge).handle { p, error ->
                if (error != null) {
                    deferred.reject(error)
                } else {
                    destFile.writeBytes(pledge.toByteArray())
                    deferred.resolve(pledge)
                }
            }
        } fail { error ->
            deferred.reject(error)
        }

        return deferred.promise
    }

    /**
     * Returns a read only observable list of unclaimed/unrevoked pledges that updates when the project is refreshed
     * or new pledges become visible on disk. May block waiting for the backend.
     */
    public fun mirrorOpenPledges(forProject: Project, executor: AffinityExecutor): ObservableSet<LHProtos.Pledge> =
        // Must build the mirror on the backend thread because otherwise it might change whilst we're doing the
        // initial copy to fill it up.
        this.executor.fetchFrom {
            ObservableMirrors.mirrorSet(getPledgesFor(forProject), executor)
        }

    private fun getPledgesFor(forProject: Project) = pledges.getOrPut(forProject) { FXCollections.observableSet<LHProtos.Pledge>() }

    /** Returns a reactive property that sums up the total value of all open pledges.  */
    public fun makeTotalPledgedProperty(project: Project, executor: AffinityExecutor): LongProperty =
            bindTotalPledgedProperty(mirrorOpenPledges(project, executor))

    public fun mirrorCheckStatuses(executor: AffinityExecutor): ObservableMap<Project, CheckStatus> =
            executor.fetchFrom { ObservableMirrors.mirrorMap(checkStatuses, executor) }

    fun fetchTotalPledged(project: Project) = executor.fetchFrom {
        getPledgesFor(project).map { it.pledgeDetails.totalInputValue.asCoin() }.fold(Coin.ZERO) { left, right -> left.add(right) }
    }

    @Throws(VerificationException::class)
    override fun notifyNewBestBlock(block: StoredBlock?) {
        executor.checkOnThread()
        // In the app, use a new block as a hint to go back and ask the server for an update (e.g. in case
        // any pledges were revoked). This also ensures the project page can be left open and it'll update from
        // time to time, which is nice if you just want it running in the corner of a room or on a projector,
        // etc.

        // TODO: Get rid of this and just use a scheduled job (issue 110).

        if (mode === Mode.CLIENT) {
            // Don't bother with pointless/noisy server requeries until we're caught up with the chain tip.
            if (block!!.height > bitcoin.peers.mostCommonChainHeight - 2) {
                log.info("New block found, refreshing pledges")
                projects.filter { it.isServerAssisted }.forEach { jitteredServerRequery(it) }
            }
        }
    }

    private var maxJitterSeconds = BLOCK_PROPAGATION_TIME_SECS

    public fun setMaxJitterSeconds(maxJitterSeconds: Int) {
        this.maxJitterSeconds = maxJitterSeconds
    }

    private fun jitteredServerRequery(project: Project) {
        jitteredExecute(Runnable { refreshProjectStatusFromServer(project) }, 15);
    }

    // Always wait at least baseSeconds to allow for block propagation and processing of revocations server-side
    // and then smear requests over another baseSeconds.
    private fun jitteredExecute(runnable: Runnable, baseSeconds: Int) {
        val jitterSeconds = Math.min(maxJitterSeconds, baseSeconds + (Math.random() * baseSeconds).toInt())
        log.info("Scheduling execution in {} seconds", jitterSeconds)
        executor.executeIn(Duration.ofSeconds(jitterSeconds.toLong()), runnable)
    }

    /**
     * Used by the server when a pledge arrives via HTTPS.

     * Does some fast stateless checks the given pledge on the calling thread and then hands off to the backend
     * thread. The backend broadcasts the pledge's dependencies, if any, and then does a revocation check. Once all
     * the checks pass, the pledge will show up in the projects pledge list.

     * It may seem pointless to do a revocation check after broadcasting dependencies. However, the dependencies may
     * themselves be double spent and the remote nodes may either notice this and send us a reject message (which we
     * ignore), or, not notice and at that point the dependencies would be considered as orphans (which we also won't
     * notice). However once we query the pledge itself, we'll discover it didn't enter the mempool UTXO set because
     * the dependencies didn't connect to a UTXO and not bother saving it to disk as result.

     * The reason we broadcast dependencies on behalf of the client is so we can return a success/failure code: if the
     * app gets back a HTTP 200 OK, the pledge should be considered valid. But if the client had to broadcast his own
     * transactions then there's a race because the app doesn't know if the server saw the dependencies yet, and might
     * get a bogus rejection.

     * If there's an error, the returned future either completes exceptionally OR returns null.
     */
    public fun submitPledge(project: Project, pledge: LHProtos.Pledge): CompletableFuture<LHProtos.Pledge> {
        // Can be on any thread.
        val result = CompletableFuture<LHProtos.Pledge>()
        try {
            project.fastSanityCheck(pledge)
            log.info("Pledge passed fast sanity check")
            // Maybe broadcast the dependencies first.
            var broadcast = CompletableFuture<LHProtos.Pledge>()
            if (pledge.transactionsCount > 1)
                broadcast = broadcastDependenciesOf(pledge)
            else
                broadcast.complete(null)
            // Switch to backend thread.
            broadcast.handleAsync(BiFunction<LHProtos.Pledge, Throwable, Any> { pledge, ex ->
                if (ex != null) {
                    result.completeExceptionally(ex)
                } else {
                    try {
                        // Check we don't accept too many pledges. This can happen if there's a buggy client or if users
                        // are submitting pledges more or less in parallel - running on the backend thread here should
                        // eliminate any races from that and ensure only one pledge wins.
                        val total = fetchTotalPledged(project)
                        val value = pledge.pledgeDetails.totalInputValue.asCoin()
                        if (total + value > project.goalAmount) {
                            log.error("Too much money submitted! {} already vs {} in new pledge", total, value)
                            throw Ex.GoalExceeded()
                        }
                        // Once dependencies (if any) are handled, start the check process. This will update pledges once
                        // done successfully.
                        checkPledgeAgainstP2PNetwork(project, pledge).whenComplete { it, ex2 ->
                            if (ex2 != null) {
                                result.completeExceptionally(ex2)
                            } else {
                                // Finally, save to disk. This will cause a notification of a new pledge to happen but we'll end
                                // up ignoring it because we'll see we already loaded and verified it.
                                savePledge(pledge)
                                result.complete(pledge)
                            }
                        }
                    } catch (e: Exception) {
                        result.completeExceptionally(e)
                    }
                }
            }, executor)
        } catch (e: Exception) {
            result.completeExceptionally(e)
        }
        return result
    }

    private fun savePledge(pledge: LHProtos.Pledge) {
        val bits = pledge.toByteArray()
        val hash = Sha256Hash.of(bits)
        // This file name is not very helpful for sysadmins. Perhaps if we scrub the metadata enough we can make a
        // better one, e.g. with the users contact details in.
        val filename = hash.toString() + PLEDGE_FILE_EXTENSION
        val path = AppDirectory.dir().resolve(filename)
        path.toFile().writeBytes(bits)
    }

    private fun broadcastDependenciesOf(pledge: LHProtos.Pledge): CompletableFuture<LHProtos.Pledge> {
        checkArgument(pledge.transactionsCount > 1)
        val result = CompletableFuture<LHProtos.Pledge>()
        log.info("Pledge has {} dependencies", pledge.transactionsCount - 1)
        executor.executeASAP {
            try {
                val txnBytes = pledge.transactionsList.subList(0, pledge.transactionsCount - 1)
                if (txnBytes.size() > 5) {
                    // We don't accept ridiculous number of dependency txns. Even this is probably too much.
                    log.error("Too many dependencies: {}", txnBytes.size())
                    result.completeExceptionally(Ex.TooManyDependencies(txnBytes.size()))
                } else {
                    log.info("Broadcasting {} provided pledge dependencies", txnBytes.size())
                    txnBytes.map { Transaction(params, it.toByteArray()) }.forEach {
                        // Wait for each broadcast in turn. In the local node case this will complete immediately. In the
                        // case of remote nodes (maybe we should forbid this later), it may block for a few seconds whilst
                        // the transactions propagate.
                        log.info("Broadcasting dependency {} with thirty second timeout", it.hash)
                        bitcoin.peers.broadcastTransaction(it).future().get(30, TimeUnit.SECONDS)
                    }
                    result.complete(pledge)
                }
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    public fun setMinPeersForUTXOQuery(minPeersForUTXOQuery: Int) {
        this.minPeersForUTXOQuery = minPeersForUTXOQuery
    }

    /** Map of project ID to project state info  */
    public fun mirrorProjectStates(runChangesIn: AffinityExecutor): ObservableMap<Sha256Hash, ProjectStateInfo> {
        return ObservableMirrors.mirrorMap(projectStates, runChangesIn)
    }

    @Synchronized public fun getProjectFromURL(uri: URI): Project? = projectsByUrlPath[uri.path]

    private inner class BloomFilterManager : OnTransactionBroadcastListener, PeerFilterProvider {
        private var allPledges: Map<TransactionOutPoint, LHProtos.Pledge>? = null

        // Methods in logical sequence of how they are used/called.

        override fun getEarliestKeyCreationTime(): Long = Utils.currentTimeSeconds()   // Unused

        override fun beginBloomFilterCalculation() {
            allPledges = executor.fetchFrom { getAllOpenPledgedOutpoints() }
        }

        override fun getBloomFilterElementCount() = allPledges!!.size()

        override fun getBloomFilter(size: Int, falsePositiveRate: Double, nTweak: Long): BloomFilter {
            val filter = BloomFilter(size, falsePositiveRate, nTweak)
            for (pledge in allPledges!!.keySet()) {
                filter.insert(pledge.bitcoinSerialize())
            }
            log.info("Bloom filter calculated")
            return filter
        }

        override fun isRequiringUpdateAllBloomFilter() = false

        override fun endBloomFilterCalculation() {
            allPledges = null
        }

        override fun onTransaction(peer: Peer, t: Transaction) {
            executor.checkOnThread()
            // TODO: Gate this logic on t being announced by multiple peers.
            checkForRevocation(t)
            // TODO: Watch out for the confirmation. If no confirmation of the revocation occurs within N hours, alert the user.
        }
    }

    public fun checkForRevocation(t: Transaction) {
        log.debug("Checking {} to see if it's a revocation", t.hash)
        val revoked = whichPledgesAreRevokedBy(t)
        if (revoked.isEmpty()) return
        for (pledges in this.pledges.values()) {
            pledges.removeAll(revoked)
        }
    }

    @Throws(VerificationException::class)
    override fun receiveFromBlock(tx: Transaction, block: StoredBlock, blockType: AbstractBlockChain.NewBlockType, relativityOffset: Int) {
        if (blockType !== AbstractBlockChain.NewBlockType.BEST_CHAIN) return
        checkForRevocation(tx)
    }

    @Throws(VerificationException::class)
    override fun notifyTransactionIsInBlock(txHash: Sha256Hash, block: StoredBlock, blockType: AbstractBlockChain.NewBlockType, relativityOffset: Int): Boolean {
        // TODO: Watch out for the confirmation. If no confirmation of the revocation occurs within N hours, alert the user.
        return super.notifyTransactionIsInBlock(txHash, block, blockType, relativityOffset)
    }

    private fun whichPledgesAreRevokedBy(t: Transaction): List<LHProtos.Pledge> {
        val result = ArrayList<LHProtos.Pledge>()
        val project = getProjectFromClaim(t)
        val inputs = t.inputs
        val outpointsToPledges = getAllOpenPledgedOutpoints()
        for (i in inputs.indices) {
            val input = inputs.get(i)
            val pledge = outpointsToPledges.get(input.outpoint) ?: continue
            // Irrelevant input.
            log.info("Broadcast tx {} input {} matches pledge {}", t.hash, i, LHUtils.hashFromPledge(pledge))
            if (project != null) {
                val tx = pledgeToTx(params, pledge)
                if (LHUtils.compareOutputsStructurally(tx, project)) {
                    // This transaction is a claim for a project we know about, and this input is claiming a pledge
                    // for that project, so skip. We must still process other inputs because they may revoke other
                    // pledges that are not being claimed.
                    log.info("... and is claim")
                    continue
                }
            }
            log.info("... and is a revocation")
            result.add(pledge)
        }
        if (result.size() > 1)
            log.info("TX {} revoked {} pledges", t.hash, result.size())
        return result
    }

    private var manager: BloomFilterManager? = null

    private fun installBloomFilterProvider() {
        manager = BloomFilterManager()
        with(bitcoin.peers) {
            addPeerFilterProvider(manager)
            addOnTransactionBroadcastListener(executor, manager)
        }
    }

    public fun shutdown() {
        executor.service.submit {
            with(bitcoin.peers) {
                removePeerFilterProvider(manager)
                removeOnTransactionBroadcastListener(manager)
            }
        }.get()
        executor.service.shutdown()
        executor.service.awaitTermination(10, TimeUnit.SECONDS)
    }

    public fun refreshBloomFilter() {
        bitcoin.peers.recalculateFastCatchupAndFilter(PeerGroup.FilterRecalculateMode.SEND_IF_CHANGED)
    }

    private fun getAllOpenPledgedOutpoints(): Map<TransactionOutPoint, LHProtos.Pledge> {
        executor.checkOnThread()
        val result = HashMap<TransactionOutPoint, LHProtos.Pledge>()
        for (pledges in this.pledges.values()) {
            for (pledge in pledges) {
                if (pledge.pledgeDetails.hasOrigHash()) continue   // Can't watch for revocations of scrubbed pledges.
                val tx = LHUtils.pledgeToTx(params, pledge)
                for (input in tx.inputs) {
                    result.put(input.outpoint, pledge)
                }
            }
        }
        return result
    }
}
