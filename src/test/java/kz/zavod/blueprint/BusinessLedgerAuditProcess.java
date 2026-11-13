package kz.zavod.blueprint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import kz.zavod.ZavodApplication;
import kz.zavod.blueprint.lifecycle.BusinessLedgerAuditService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Explicit offline operator command (NOT a public endpoint or automatic
 * production scheduler). Uses configured DB read credentials.
 *
 * Args: AUDIT_REPORT.json [TRUSTED_PREVIOUS_ANCHORS.json]
 * Previous anchors are stored outside the DB by the operator and protected by
 * independent access control. Never replace trusted anchors with a report from
 * the same untrusted database without reviewing the result.
 */
public final class BusinessLedgerAuditProcess {
    private BusinessLedgerAuditProcess() {}
    public static void main(String[] args) throws Exception {
        if(args.length<1||args.length>2)
            throw new IllegalArgumentException("Usage: BusinessLedgerAuditProcess REPORT_JSON [TRUSTED_ANCHORS_JSON]");
        Path output=Path.of(args[0]);
        if(Files.exists(output))
            throw new IllegalStateException("AUDIT_REPORT_EXISTS");
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        Map<String,BusinessLedgerAuditService.Anchor> anchors=Collections.emptyMap();
        if(args.length==2) {
            Path pinned=Path.of(args[1]);
            if(!Files.isRegularFile(pinned))
                throw new IllegalStateException("TRUSTED_ANCHOR_FILE_MISSING");
            anchors=json.readValue(pinned.toFile(),
                    new TypeReference<Map<String,BusinessLedgerAuditService.Anchor>>(){});
        }
        BusinessLedgerAuditService.AuditReport report;
        try(var app=new SpringApplicationBuilder(ZavodApplication.class)
                .web(WebApplicationType.NONE).run()) {
            report=app.getBean(BusinessLedgerAuditService.class).auditAll(anchors);
        }
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        System.out.println("G42_FULL_LEDGER_AUDIT "+(report.passed()?"PASS":"FAIL")+
                " sites="+report.sites().size()+" report="+output);
        if(!report.passed())System.exit(3);
    }
}
