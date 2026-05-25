package ru.tkbbank.sbprouter.management;
public class ConfigValidationException extends RuntimeException {
    private final String field;
    public ConfigValidationException(String field, String message) { super(message); this.field = field; }
    public String getField() { return field; }
}
