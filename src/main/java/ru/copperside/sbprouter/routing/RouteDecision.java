package ru.copperside.sbprouter.routing;

public record RouteDecision(String upstreamName, TerminalOwner terminalOwner, String requestType) {}
