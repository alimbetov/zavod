package kz.zavod.studio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kz.zavod.studio.StudioViewLayout.*;

/** Layout uses its own optimistic revision, never the engineering graph revision. */
@Service
public class StudioLayoutService {
    private final StudioBlueprintService blueprints;
    private final StudioLayoutRepository layouts;
    private final ObjectMapper json;
    public StudioLayoutService(StudioBlueprintService blueprints,StudioLayoutRepository layouts,ObjectMapper json){
        this.blueprints=blueprints;this.layouts=layouts;this.json=json;
    }
    private static String id(UUID blueprintId,String viewId) {
        if(viewId==null||!viewId.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IllegalArgumentException("INVALID_VIEW_ID");
        return blueprintId+":"+viewId;
    }
    @Transactional(readOnly=true)
    public LayoutResponse load(String siteId,UUID blueprintId,String viewId) {
        blueprints.requireOwner(siteId,blueprintId);
        var row=layouts.findById(id(blueprintId,viewId))
                .orElseThrow(()->new StudioNotFoundException("LAYOUT_NOT_FOUND"));
        if(!siteId.equals(row.siteId()))throw new StudioNotFoundException("LAYOUT_NOT_FOUND");
        try{return new LayoutResponse(viewId,row.layoutVersion(),row.blueprintRevision(),
                    json.readValue(row.layoutJson(),LayoutBody.class));}
        catch(JsonProcessingException ex){throw new IllegalStateException("CORRUPTED_CANVAS_LAYOUT",ex);}
    }
    @Transactional
    public LayoutResponse save(String siteId,UUID blueprintId,String viewId,SaveLayout command) {
        Objects.requireNonNull(command);
        blueprints.requireOwner(siteId,blueprintId);
        ProcessBlueprint b=blueprints.load(siteId,blueprintId);
        if(b.revision()!=command.blueprintRevision())
            throw new StudioConflictException("STALE_BLUEPRINT_FOR_LAYOUT");
        var unit=b.units().stream().filter(u->u.id().equals(command.layout().unitId())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("UNKNOWN_LAYOUT_UNIT"));
        Set<String> knownIds=new HashSet<>();
        unit.equipment().forEach(e->knownIds.add(e.id()));
        if(!knownIds.containsAll(command.layout().positions().keySet()))
            throw new IllegalArgumentException("UNKNOWN_LAYOUT_EQUIPMENT");
        String key=id(blueprintId,viewId);
        String serialized;
        try{serialized=json.writeValueAsString(command.layout());}
        catch(JsonProcessingException ex){throw new IllegalStateException("INVALID_LAYOUT_JSON",ex);}
        long next=command.expectedLayoutVersion()+1;
        if(command.expectedLayoutVersion()==0) {
            try{layouts.saveAndFlush(new StudioLayoutEntity(key,siteId,blueprintId.toString(),
                    viewId,next,b.revision(),serialized));}
            catch(DataIntegrityViolationException ex){throw new StudioConflictException("LAYOUT_ALREADY_EXISTS");}
        } else {
            if(layouts.compareAndUpdate(key,command.expectedLayoutVersion(),next,b.revision(),serialized)!=1)
                throw new StudioConflictException("STALE_LAYOUT_VERSION");
        }
        return new LayoutResponse(viewId,next,b.revision(),command.layout());
    }
}
