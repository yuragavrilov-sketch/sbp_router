package ru.copperside.sbprouter.extraction;

/** The GCSvc facts the proxy reads from a request: per-message correlation id (stan), message type,
 *  per-operation id (SbpOperId), and the SBP operation classifier + coarse operation type. */
public record GcsvcMessageInfo(String correlationId, String messageType, String operationId,
                               String sbpOperation, String operationType) {
}
