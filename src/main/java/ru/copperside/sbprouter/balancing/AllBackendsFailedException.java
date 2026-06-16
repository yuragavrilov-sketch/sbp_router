package ru.copperside.sbprouter.balancing;

/** Raised when every candidate backend (up to the failover cap) failed with a transport error. */
public class AllBackendsFailedException extends RuntimeException {

    private final boolean lastWasTimeout;

    public AllBackendsFailedException(boolean lastWasTimeout) {
        super("all backend attempts failed" + (lastWasTimeout ? " (last was a timeout)" : ""));
        this.lastWasTimeout = lastWasTimeout;
    }

    public boolean lastWasTimeout() {
        return lastWasTimeout;
    }
}
