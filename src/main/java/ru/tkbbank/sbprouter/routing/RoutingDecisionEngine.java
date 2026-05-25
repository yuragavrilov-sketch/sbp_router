package ru.tkbbank.sbprouter.routing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.ExtractionResult;
import ru.tkbbank.sbprouter.management.ConfigStore;

@Component
public class RoutingDecisionEngine {
    private static final String INFOSRV = "infosrv";
    private static final String STUB_VERIFICATION = "stub-verification";
    private static final String STUB_C2BQRD_VERIFICATION = "stub-c2bqrd-verification";
    private static final String STUB_CONNECTOR = "stub-connector";
    private static final String C2BQRD_RCV = "C2BQRD_Rcv";

    private final ConfigStore configStore;

    @Autowired
    public RoutingDecisionEngine(ConfigStore configStore) { this.configStore = configStore; }

    RoutingDecisionEngine(SbpRouterProperties.Routing routing) {
        this(new ConfigStore(RouterConfigSnapshot.builder().routing(routing).build()));
    }

    public RouteDecision decide(ExtractionResult extraction, TerminalOwner terminalOwner) {
        SbpRouterProperties.Routing routing = configStore.current().routing();
        String requestType = extraction.requestType();

        if ("ReqAuthPay".equals(requestType) && C2BQRD_RCV.equals(extraction.field("sbpOperation"))) {
            return new RouteDecision(STUB_C2BQRD_VERIFICATION, terminalOwner, requestType);
        }

        if (requestType == null || !routing.isTkbPayEnabled() || terminalOwner == TerminalOwner.EXTERNAL) {
            return new RouteDecision(INFOSRV, terminalOwner, requestType);
        }
        String upstream = switch (requestType) {
            case "ReqAuthPay" -> STUB_VERIFICATION;
            case "ReqNoticePay" -> STUB_CONNECTOR;
            default -> INFOSRV;
        };
        return new RouteDecision(upstream, terminalOwner, requestType);
    }
}
