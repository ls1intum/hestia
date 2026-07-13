package app.ai;

/** Group of runtime exceptions the controllers can branch on. */
public final class AiExceptions {
    private AiExceptions() {}

    /** Underlying provider returned HTTP 429. */
    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String msg) { super(msg); }
    }

    /** Underlying provider returned HTTP 402 (credits exhausted). */
    public static class PaymentRequiredException extends RuntimeException {
        public PaymentRequiredException(String msg) { super(msg); }
    }

    /** Provider returned a non-2xx that isn't rate-limit / payment-required, or a transport failure (status 0). */
    public static class ProviderException extends RuntimeException {
        private final int status;
        public ProviderException(String msg, int status) {
            super(msg);
            this.status = status;
        }
        public int status() { return status; }
    }

    /**
     * The provider answered 2xx but the payload was unusable: no tool call,
     * invalid JSON in the tool args, or an unparseable response envelope.
     * Consumers branch on this type — never on the message text.
     */
    public static class MalformedModelOutputException extends ProviderException {
        public MalformedModelOutputException(String msg) { super(msg, 0); }
    }

    /**
     * Single source of truth for "worth retrying at orchestration level":
     * rate limits, 5xx, timeouts, and transport failures. Malformed model
     * output is deliberately excluded — re-asking is a caller-level decision
     * (e.g. the solve services re-prompt with a stronger instruction).
     */
    public static boolean isTransient(RuntimeException e) {
        if (e instanceof RateLimitException) return true;
        if (e instanceof MalformedModelOutputException) return false;
        if (e instanceof ProviderException pe) {
            int s = pe.status();
            return s == 0 || s == 408 || s == 425 || s == 429 || s >= 500;
        }
        return false;
    }
}
