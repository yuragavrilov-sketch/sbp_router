package ru.tkbbank.sbprouter.management;
public class VersionConflictException extends RuntimeException {
    public VersionConflictException(long expected, long actual) {
        super("Config version conflict: expected " + expected + " but current is " + actual);
    }
}
