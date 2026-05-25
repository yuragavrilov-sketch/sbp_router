package ru.tkbbank.sbprouter.management.dto;

public record StatusDto(boolean up, long uptimeSeconds, boolean tkbPayEnabled, long configVersion, int historySize, int historyCapacity) {}
