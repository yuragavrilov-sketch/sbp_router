package ru.copperside.sbprouter.routing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.copperside.sbprouter.config.SbpRouterProperties;
import ru.copperside.sbprouter.manifest.RoutingConfigHolder;
import java.util.Map;
import java.util.Set;

@Component
public class TerminalDetector {
    private final RoutingConfigHolder holder;

    @Autowired
    public TerminalDetector(RoutingConfigHolder holder) { this.holder = holder; }

    TerminalDetector(SbpRouterProperties.Terminals terminals) {
        SbpRouterProperties p = new SbpRouterProperties();
        p.setTerminals(terminals);
        this.holder = new RoutingConfigHolder(p);
    }

    public TerminalOwner detect(Map<String, String> fields) {
        SbpRouterProperties.Terminals terminals = holder.getTerminals();
        java.util.List<String> rawList = terminals.getTkbPayList();
        Set<String> tkbPayList = rawList != null ? Set.copyOf(rawList) : Set.of();
        String c2bFieldName = terminals.getC2bTerminal().getFieldName();
        String b2cFieldName = terminals.getB2cTerminal().getFieldName();
        String b2cPrefix = terminals.getB2cTerminal().getTkbPayPrefix();
        String operType = fields.get("sbpOperType");
        if (operType == null) return TerminalOwner.EXTERNAL;
        if (operType.toUpperCase().startsWith("C2B")) {
            String tspId = fields.get(c2bFieldName);
            return (tspId != null && tkbPayList.contains(tspId)) ? TerminalOwner.TKB_PAY : TerminalOwner.EXTERNAL;
        }
        if (operType.toUpperCase().startsWith("B2C")) {
            String termName = fields.get(b2cFieldName);
            return (termName != null && termName.startsWith(b2cPrefix)) ? TerminalOwner.TKB_PAY : TerminalOwner.EXTERNAL;
        }
        return TerminalOwner.EXTERNAL;
    }
}
