package ru.copperside.sbprouter.routing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.copperside.sbprouter.config.SbpRouterProperties;
import ru.copperside.sbprouter.extraction.ExtractionResult;
import ru.copperside.sbprouter.manifest.RoutingConfigHolder;

@Component
public class RoutingDecisionEngine {
    private static final String INFOSRV = "infosrv";
    private static final String STUB_VERIFICATION = "stub-verification";
    private static final String STUB_C2BQRD_VERIFICATION = "stub-c2bqrd-verification";
    private static final String STUB_CONNECTOR = "stub-connector";
    private static final String C2BQRD_RCV = "C2BQRD_Rcv";

    private final RoutingConfigHolder holder;

    @Autowired
    public RoutingDecisionEngine(RoutingConfigHolder holder) { this.holder = holder; }

    RoutingDecisionEngine(SbpRouterProperties.Routing routing) {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setRouting(routing);
        this.holder = new RoutingConfigHolder(p);
    }

    public RouteDecision decide(ExtractionResult extraction, TerminalOwner terminalOwner) {
        SbpRouterProperties.Routing routing = holder.getRouting();
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
