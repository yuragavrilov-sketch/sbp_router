package ru.tkbbank.sbprouter.routing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.RouterConfigSnapshot;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.management.ConfigStore;
import java.util.Map;
import java.util.Set;

@Component
public class TerminalDetector {
    private final ConfigStore configStore;

    @Autowired
    public TerminalDetector(ConfigStore configStore) { this.configStore = configStore; }

    TerminalDetector(SbpRouterProperties.Terminals terminals) {
        this(new ConfigStore(RouterConfigSnapshot.builder().terminals(terminals).build()));
    }

    public TerminalOwner detect(Map<String, String> fields) {
        SbpRouterProperties.Terminals terminals = configStore.current().terminals();
        Set<String> tkbPayList = Set.copyOf(terminals.getTkbPayList());
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
