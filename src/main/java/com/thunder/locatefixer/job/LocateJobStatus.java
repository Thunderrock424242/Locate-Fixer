package com.thunder.locatefixer.job;

public enum LocateJobStatus {
    QUEUED(false),
    INDEX_LOOKUP(false),
    SEARCHING(false),
    BACKEND_SEARCH(false),
    FOUND(true),
    NOT_FOUND(true),
    UNAVAILABLE(true),
    FAILED(true),
    CANCELLED(true),
    TIMED_OUT(true);

    private final boolean terminal;

    LocateJobStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
