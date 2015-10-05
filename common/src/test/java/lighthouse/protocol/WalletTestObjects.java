package lighthouse.protocol;

import com.google.common.base.*;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.params.*;
import org.bitcoinj.script.*;
import org.bitcoinj.store.*;
import org.bitcoinj.testing.*;
import org.slf4j.*;

import java.util.*;

import static com.google.common.base.Preconditions.*;

/**
 * A set of objects that are useful when writing unit tests that require a wallet.
 */
public class WalletTestObjects {
    private static final Logger log = LoggerFactory.getLogger(WalletTestObjects.class);

    public final Wallet wallet;
    public final BlockChain chain;
    public final MemoryBlockStore store;
    public final NetworkParameters params;
    public final MockTransactionBroadcaster broadcaster;

    public WalletTestObjects() {
        this(Suppliers.ofInstance(new Wallet(UnitTestParams.get())));
    }

    public WalletTestObjects(Supplier<? extends Wallet> walletFactory) {
        try {
            wallet = walletFactory.get();
            wallet.setKeyChainGroupLookaheadSize(2);
            checkArgument(wallet.getParams() == UnitTestParams.get());
            params = wallet.getParams();
            store = new MemoryBlockStore(params);
            chain = new BlockChain(params, wallet, store);
            broadcaster = new MockTransactionBroadcaster(wallet);
        } catch (BlockStoreException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    public void sendAmounts(long... amounts) {
        sendAmounts(new Random(), amounts);
    }

    /**
     * Create a set of fake transactions that send outputs in the given amounts to the wallet, distributed across
     * a random number of transactions, all in one block.
     */
    public void sendAmounts(Random random, long... amounts) {
        int numTxns = Math.max(1, random.nextInt(amounts.length));
        log.info("sendAmounts creating {} txns", numTxns);
        final double outputsPerTx = amounts.length / (double) numTxns;
        checkState(outputsPerTx >= 1.0);
        double cursor = 0.0;
        int numOutsSoFar = 0;
        ECKey feederKey = new ECKey();
        for (int i = 0; i < amounts.length; /* nothing */) {
            Transaction currentTx = new Transaction(params);
            cursor += outputsPerTx;
            long totalVal = 0;
            int j = 0;
            for (; j < ((int) cursor) - numOutsSoFar; j++) {
                long val = amounts[i++];
                totalVal += val;
                currentTx.addOutput(Coin.valueOf(val), wallet.freshReceiveKey().toAddress(params));
            }
            numOutsSoFar += j;
            Transaction fakeFeeder = new Transaction(params);
            TransactionOutput output = fakeFeeder.addOutput(Coin.valueOf(totalVal), feederKey);
            currentTx.addInput(output).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));
            currentTx = FakeTxBuilder.roundTripTransaction(params, currentTx);
            // currentTx now "looks real" except for the fake input signature, but that's not checked anywhere as it
            // would not be ours anyway.
            final Transaction tx = currentTx;
            receiveViaBlock(tx);
        }
    }

    public void receiveViaBlock(Transaction... txns) {
        Block block = chain.getChainHead().getHeader().createNextBlock(null);
        for (Transaction tx : txns) {
            block.addTransaction(tx);
        }
        block.solve();
        try {
            chain.add(block);
        } catch (PrunedException e) {
            throw new RuntimeException(e);
        }
    }
}
