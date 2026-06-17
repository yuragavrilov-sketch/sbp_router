package ru.copperside.sbprouter.extraction;

/** The minimal GCSvc facts the flat proxy reads: correlation id (stan) and message type. */
public record GcsvcMessageInfo(String correlationId, String messageType) {
}
