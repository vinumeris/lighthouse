package lighthouse.protocol;

// Gather all exception definitions in one file, for convenience.
public class Ex extends RuntimeException {
    public static class NoTransactionData extends Ex {}

    public static class BadTX extends Ex {}
    public static class TxWrongNumberOfOutputs extends BadTX {
        public final int actual;
        public final int expected;

        public TxWrongNumberOfOutputs(int actual, int expected) {
            this.actual = actual;
            this.expected = expected;
        }
    }
    public static class OutputMismatch extends BadTX {}
    public static class UnknownUTXO extends BadTX {
        @Override
        public String getMessage() {
            return "Unknown UTXO";
        }
    }
    public static class InconsistentUTXOAnswers extends Ex {}
    // Used when the totalInputValue field of the pledge protobuf doesn't match what the tx actually does.
    public static class CachedValueMismatch extends BadTX {}
    // Used when the pledges add up to more or less than the contract value.
    public static class ValueMismatch extends Ex {
        public final long byAmount;
        public ValueMismatch(long byAmount) {
            this.byAmount = byAmount;
        }

        @Override
        public String toString() {
            return "Ex.ValueMismatch{byAmount=" + byAmount + "}";
        }
    }
    // Bad script executions == ScriptException
    public static class TooManyDependencies extends Ex {
        public final int amount;
        public TooManyDependencies(int amount) {
            this.amount = amount;
        }
    }

    public static class NonStandardInput extends Ex {}

    public static class P2SHPledge extends Ex {}
}
