package ru.copperside.sbprouter.manifest;

/** Thrown when a fetched manifest is structurally invalid and must not replace the current snapshot. */
public class ManifestValidationException extends RuntimeException {
    public ManifestValidationException(String message) {
        super(message);
    }
}
