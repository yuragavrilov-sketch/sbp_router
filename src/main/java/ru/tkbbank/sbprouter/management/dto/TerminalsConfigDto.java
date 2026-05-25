package ru.tkbbank.sbprouter.management.dto;

public record TerminalsConfigDto(String c2bFieldName, String b2cFieldName, String b2cPrefix, java.util.List<String> tkbPayList) {}
