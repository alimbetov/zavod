package kz.zavod.telemetry;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kz.zavod.telemetry.TelemetryContracts.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Local-only TF-03. Returns only verified, site-scoped, version-bound,
 * SIMULATED readings. This is not a production or industrial OT endpoint.
 */
@RestController
@Profile("dev-local")
@RequestMapping("/api/v1/sites/{siteId}/blueprints/{blueprintId}/revisions/{revision}/telemetry")
public class TelemetryMonitorDevController {
    public record SignalView(SignalDefinition definition,Reading latest,
                             List<TelemetrySample> history) {}
    private final SignalRegistryService registry;
    private final TelemetryIngestionService ingestion;

    public TelemetryMonitorDevController(SignalRegistryService registry,
                                         TelemetryIngestionService ingestion) {
        this.registry=registry;this.ingestion=ingestion;
    }

    @GetMapping
    public List<SignalView> signals(@PathVariable String siteId,@PathVariable UUID blueprintId,
                                    @PathVariable long revision,
                                    @RequestParam(required=false) String unitId,
                                    @RequestParam(defaultValue="60") int minutes,
                                    @RequestParam(defaultValue="100") int limit) {
        if(minutes<1||minutes>1440||limit<1||limit>100)
            throw new IllegalArgumentException("INVALID_TELEMETRY_WINDOW");
        Instant now=Instant.now();
        Instant from=now.minus(minutes,ChronoUnit.MINUTES);
        var bindings=registry.list(siteId,blueprintId,revision).stream()
                .filter(def->unitId==null||unitId.equals(def.unitId())).toList();
        if(bindings.size()>100)
            throw new IllegalArgumentException("TELEMETRY_SIGNAL_LIMIT");
        return bindings.stream().map(def->new SignalView(def,
                ingestion.latest(siteId,blueprintId,revision,def.id(),now),
                ingestion.history(siteId,blueprintId,revision,def.id(),from,now,limit))).toList();
    }

    /** Explicit DEV-only simulated signal provisioning. Does not read an OT device. */
    @PostMapping("/signals")
    @ResponseStatus(HttpStatus.CREATED)
    public SignalDefinition register(@PathVariable String siteId,@PathVariable UUID blueprintId,
                                     @PathVariable long revision,@RequestBody SignalDefinition input) {
        if(!siteId.equals(input.siteId())||!blueprintId.equals(input.blueprintId())
                ||revision!=input.blueprintRevision())
            throw new IllegalArgumentException("SIGNAL_SCOPE_MISMATCH");
        return registry.registerSimulated(input);
    }

    /** DEV-only ingestion. Never accepts live EDGE_READ_ONLY measurements. */
    @PostMapping("/signals/{signalId}/samples")
    public TelemetryIngestionService.Receipt simulatedSample(
            @PathVariable String siteId,@PathVariable UUID blueprintId,
            @PathVariable long revision,@PathVariable UUID signalId,
            @RequestBody TelemetryIngestionService.Incoming input) {
        return ingestion.ingestSimulated(siteId,blueprintId,revision,signalId,input);
    }
}
