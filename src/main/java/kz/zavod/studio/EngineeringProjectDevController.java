package kz.zavod.studio;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Loopback-only dev-local CRUD. Production/default remains deny-all.
 * This is intentionally NOT the R1 corporate REST controller.
 */
@RestController
@Profile("dev-local")
@RequestMapping("/api/v1/sites/{siteId}/projects")
public class EngineeringProjectDevController {
    public record CreateRequest(String name,String description) {}
    public record UpdateRequest(long expectedVersion,String name,String description) {}

    private final EngineeringProjectService service;
    public EngineeringProjectDevController(EngineeringProjectService service){this.service=service;}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EngineeringProjectService.ProjectView create(
            @PathVariable String siteId,@RequestBody CreateRequest request) {
        return service.create(siteId,request.name(),request.description());
    }
    @GetMapping
    public List<EngineeringProjectService.ProjectView> list(@PathVariable String siteId){
        return service.list(siteId);
    }
    @GetMapping("/{projectId}")
    public EngineeringProjectService.ProjectView get(@PathVariable String siteId,
                                                     @PathVariable UUID projectId){
        return service.get(siteId,projectId);
    }
    @PutMapping("/{projectId}")
    public EngineeringProjectService.ProjectView update(@PathVariable String siteId,
                                                        @PathVariable UUID projectId,
                                                        @RequestBody UpdateRequest request){
        return service.update(siteId,projectId,request.expectedVersion(),request.name(),request.description());
    }
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable String siteId,@PathVariable UUID projectId,
                        @RequestParam long expectedVersion){
        service.archive(siteId,projectId,expectedVersion);
    }
}
