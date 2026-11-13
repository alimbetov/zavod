package kz.zavod.blueprint.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Server authority for append-only Blueprint drafts; no PLC commands. */
@Service
public class BlueprintDraftService {
    public static final String SCHEMA_VERSION="blueprint.v1";
    private final BlueprintRevisionRepository repository;
    private final BlueprintHeadRepository headRepository;
    private final ObjectMapper objectMapper;

    public BlueprintDraftService(BlueprintRevisionRepository repository,BlueprintHeadRepository headRepository,
                                 ObjectMapper objectMapper) {
        this.repository=repository;
        this.headRepository=headRepository;
        this.objectMapper=objectMapper;
    }

    /** Optimistic revision sequence: expectedPreviousRevision=0 for a new blueprint. */
    @Transactional
    public void saveDraft(ProcessBlueprint snapshot,long expectedPreviousRevision) {
        Objects.requireNonNull(snapshot);
        if(snapshot.revision()!=expectedPreviousRevision+1 || expectedPreviousRevision<0)
            throw new BlueprintRevisionConflict("expected="+expectedPreviousRevision+" next="+snapshot.revision());
        String json=serialize(snapshot);
        String id=snapshot.id().toString();
        if(expectedPreviousRevision==0) {
            try {
                headRepository.saveAndFlush(new BlueprintHeadEntity(id,1));
            } catch(DataIntegrityViolationException ex) {
                throw new BlueprintRevisionConflict("Blueprint head already exists: "+id,ex);
            }
        } else {
            int advanced=headRepository.compareAndAdvance(id,expectedPreviousRevision,snapshot.revision());
            if(advanced!=1)throw new BlueprintRevisionConflict(
                    "Blueprint="+id+" expected="+expectedPreviousRevision);
        }
        try {
            repository.saveAndFlush(new BlueprintRevisionEntity(UUID.randomUUID().toString(),id,
                    snapshot.revision(),SCHEMA_VERSION,sha256(json),json));
        } catch(DataIntegrityViolationException ex) {
            throw new BlueprintRevisionConflict("Duplicate revision for Blueprint="+id,ex);
        }
    }

    @Transactional(readOnly=true)
    public ProcessBlueprint loadLatest(UUID blueprintId) {
        var row=repository.findTopByBlueprintIdOrderByRevisionNoDesc(blueprintId.toString())
                .orElseThrow(()->new IllegalArgumentException("Blueprint not found"));
        return read(row);
    }

    @Transactional(readOnly=true)
    public ProcessBlueprint loadRevision(UUID blueprintId,long revisionNo) {
        var row=repository.findByBlueprintIdAndRevisionNo(blueprintId.toString(),revisionNo)
                .orElseThrow(()->new IllegalArgumentException("Revision not found"));
        return read(row);
    }

    private ProcessBlueprint read(BlueprintRevisionEntity row) {
        if(!SCHEMA_VERSION.equals(row.schemaVersion()))throw new IllegalStateException("UNSUPPORTED_BLUEPRINT_SCHEMA");
        if(!sha256(row.snapshotJson()).equals(row.contentHash()))throw new IllegalStateException("CORRUPTED_BLUEPRINT_SNAPSHOT");
        ProcessBlueprint snapshot;
        try { snapshot=objectMapper.readValue(row.snapshotJson(),ProcessBlueprint.class); }
        catch(JsonProcessingException e){throw new IllegalStateException("INVALID_BLUEPRINT_SNAPSHOT",e);}
        if(!snapshot.id().toString().equals(row.blueprintId())||snapshot.revision()!=row.revisionNo())
            throw new IllegalStateException("SNAPSHOT_REVISION_MISMATCH");
        return snapshot;
    }
    private String serialize(ProcessBlueprint value) {
        try{return objectMapper.writeValueAsString(value);}
        catch(JsonProcessingException e){throw new IllegalStateException("BLUEPRINT_SERIALIZATION_FAILED",e);}
    }
    private static String sha256(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
