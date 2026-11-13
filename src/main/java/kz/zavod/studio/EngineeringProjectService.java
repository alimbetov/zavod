package kz.zavod.studio;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import kz.zavod.blueprint.lifecycle.PlantActivationSetRepository;
import kz.zavod.blueprint.lifecycle.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local engineering-project CRUD. No production identity or public API is implied. */
@Service
public class EngineeringProjectService {
    public record ProjectView(String projectId,String siteId,String name,String description,
                              String status,long version,Instant createdAt,Instant updatedAt,
                              Instant archivedAt) {}
    private final EngineeringProjectRepository projects;
    private final StudioBlueprintService blueprints;
    private final SiteRepository sites;
    private final PlantActivationSetRepository sets;

    public EngineeringProjectService(EngineeringProjectRepository projects,StudioBlueprintService blueprints,
                                     SiteRepository sites,PlantActivationSetRepository sets) {
        this.projects=projects;this.blueprints=blueprints;this.sites=sites;this.sets=sets;
    }

    /** Creates real Blueprint rev1 + project metadata atomically in one PostgreSQL transaction. */
    @Transactional
    public ProjectView create(String siteId,String name,String description) {
        validate(name,description);
        var blueprint=blueprints.create(siteId);
        var project=new EngineeringProjectEntity(blueprint.id().toString(),siteId,
                name.strip(),description==null?"":description.strip());
        return view(projects.saveAndFlush(project));
    }

    @Transactional(readOnly=true)
    public List<ProjectView> list(String siteId) {
        blueprints.requireSite(siteId);
        return projects.findBySiteIdAndStatusOrderByCreatedAtAsc(siteId,EngineeringProjectEntity.Status.ACTIVE)
                .stream().map(EngineeringProjectService::view).toList();
    }

    @Transactional(readOnly=true)
    public ProjectView get(String siteId,UUID projectId) {
        return view(requireActive(siteId,projectId));
    }

    @Transactional
    public ProjectView update(String siteId,UUID projectId,long expectedVersion,
                              String name,String description) {
        validate(name,description);
        if(expectedVersion<0)throw new IllegalArgumentException("INVALID_PROJECT_VERSION");
        requireActive(siteId,projectId);
        int updated=projects.compareAndUpdate(projectId.toString(),siteId,name.strip(),
                description==null?"":description.strip(),Instant.now(),
                EngineeringProjectEntity.Status.ACTIVE,expectedVersion);
        if(updated!=1)throw new StudioConflictException("STALE_PROJECT_VERSION");
        return view(requireActive(siteId,projectId));
    }

    /** Archive metadata only: immutable Blueprint history and approvals are never deleted. */
    @Transactional
    public void archive(String siteId,UUID projectId,long expectedVersion) {
        if(expectedVersion<0)throw new IllegalArgumentException("INVALID_PROJECT_VERSION");
        requireActive(siteId,projectId);
        // Prevent hiding a configuration that is selected as active in this Site.
        var site=sites.lockSite(siteId).orElseThrow(()->new StudioNotFoundException("SITE_NOT_FOUND"));
        if(site.activeSetId()!=null) {
            var active=sets.findById(site.activeSetId())
                    .orElseThrow(()->new StudioConflictException("INVALID_ACTIVATION_POINTER"));
            if(!siteId.equals(active.siteId()))
                throw new StudioConflictException("INVALID_ACTIVATION_POINTER");
            if(projectId.toString().equals(active.blueprintId()))
                throw new StudioConflictException("ACTIVE_PROJECT_CANNOT_ARCHIVE");
        }
        int archived=projects.compareAndArchive(projectId.toString(),siteId,expectedVersion,Instant.now(),
                EngineeringProjectEntity.Status.ACTIVE,EngineeringProjectEntity.Status.ARCHIVED);
        if(archived!=1)throw new StudioConflictException("STALE_PROJECT_VERSION");
    }

    private EngineeringProjectEntity requireActive(String siteId,UUID projectId) {
        Objects.requireNonNull(projectId,"projectId");
        blueprints.requireSite(siteId);
        return projects.findById(projectId.toString())
                .filter(project->siteId.equals(project.siteId())
                        &&project.status()==EngineeringProjectEntity.Status.ACTIVE)
                .orElseThrow(()->new StudioNotFoundException("PROJECT_NOT_FOUND"));
    }

    private static void validate(String name,String description) {
        if(name==null||name.isBlank()||name.strip().length()>160)
            throw new IllegalArgumentException("INVALID_PROJECT_NAME");
        if(description!=null&&description.strip().length()>2000)
            throw new IllegalArgumentException("INVALID_PROJECT_DESCRIPTION");
    }

    private static ProjectView view(EngineeringProjectEntity project) {
        return new ProjectView(project.projectId(),project.siteId(),project.name(),project.description(),
                project.status().name(),project.rowVersion(),project.createdAt(),project.updatedAt(),
                project.archivedAt());
    }
}
