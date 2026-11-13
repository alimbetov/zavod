-- R1-BF-01: READ-ONLY audit of ownership and historical references.
-- psql -X -v ON_ERROR_STOP=1 -f scripts/sql/r1-site-owner-audit.sql
-- Expected: every anomaly count = 0 before enterprise release.
-- Run against a backup/restored staging database first; not a repair script.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'orphan_site_members' AS finding, count(*) AS affected
FROM site_member sm LEFT JOIN site s ON s.site_id=sm.site_id
WHERE s.site_id IS NULL
UNION ALL
SELECT 'unassigned_heads' AS finding, count(*) AS affected
FROM blueprint_head h LEFT JOIN blueprint_site o ON o.blueprint_id=h.blueprint_id
WHERE o.blueprint_id IS NULL
UNION ALL
SELECT 'owner_missing_site',count(*) FROM blueprint_site o LEFT JOIN site s ON s.site_id=o.site_id WHERE s.site_id IS NULL
UNION ALL
SELECT 'owner_missing_blueprint_head',count(*) FROM blueprint_site o LEFT JOIN blueprint_head h ON h.blueprint_id=o.blueprint_id WHERE h.blueprint_id IS NULL
UNION ALL
SELECT 'revision_missing_blueprint_head',count(*) FROM blueprint_revision r LEFT JOIN blueprint_head h ON h.blueprint_id=r.blueprint_id WHERE h.blueprint_id IS NULL
UNION ALL
SELECT 'head_revision_not_present',count(*) FROM blueprint_head h LEFT JOIN blueprint_revision r
ON r.blueprint_id=h.blueprint_id AND r.revision_no=h.revision_no WHERE r.id IS NULL
UNION ALL
SELECT 'approval_site_mismatch_or_unassigned',count(*) FROM blueprint_approval a LEFT JOIN blueprint_site o ON o.blueprint_id=a.blueprint_id
WHERE o.blueprint_id IS NULL OR a.site_id IS DISTINCT FROM o.site_id
UNION ALL
SELECT 'approval_revision_missing',count(*) FROM blueprint_approval a LEFT JOIN blueprint_revision r
ON r.blueprint_id=a.blueprint_id AND r.revision_no=a.revision_no WHERE r.id IS NULL
UNION ALL
SELECT 'activation_site_mismatch_or_unassigned',count(*) FROM plant_activation_set a LEFT JOIN blueprint_site o ON o.blueprint_id=a.blueprint_id
WHERE o.blueprint_id IS NULL OR a.site_id IS DISTINCT FROM o.site_id
UNION ALL
SELECT 'activation_revision_missing',count(*) FROM plant_activation_set a LEFT JOIN blueprint_revision r
ON r.blueprint_id=a.blueprint_id AND r.revision_no=a.revision_no WHERE r.id IS NULL
UNION ALL
SELECT 'layout_site_mismatch_or_unassigned',count(*) FROM blueprint_view_layout l LEFT JOIN blueprint_site o ON o.blueprint_id=l.blueprint_id
WHERE o.blueprint_id IS NULL OR l.site_id IS DISTINCT FROM o.site_id
UNION ALL
SELECT 'layout_revision_missing',count(*) FROM blueprint_view_layout l LEFT JOIN blueprint_revision r
ON r.blueprint_id=l.blueprint_id AND r.revision_no=l.blueprint_revision WHERE r.id IS NULL
UNION ALL
SELECT 'site_active_set_cross_site_or_missing',count(*) FROM site s LEFT JOIN plant_activation_set a ON a.id=s.active_set_id
WHERE s.active_set_id IS NOT NULL AND (a.id IS NULL OR a.site_id IS DISTINCT FROM s.site_id)
UNION ALL
SELECT 'activation_previous_set_cross_site_or_missing',count(*) FROM plant_activation_set a
LEFT JOIN plant_activation_set p ON p.id=a.previous_set_id
WHERE a.previous_set_id IS NOT NULL AND (p.id IS NULL OR p.site_id IS DISTINCT FROM a.site_id)
UNION ALL
SELECT 'activation_restored_set_cross_site_or_missing',count(*) FROM plant_activation_set a
LEFT JOIN plant_activation_set p ON p.id=a.restored_from_set_id
WHERE a.restored_from_set_id IS NOT NULL AND (p.id IS NULL OR p.site_id IS DISTINCT FROM a.site_id)
ORDER BY finding;
-- The identifiers below must be resolved from enterprise inventory or explicit
-- signed business ownership evidence. NEVER infer an owner from activation pointers.
SELECT h.blueprint_id,h.revision_no FROM blueprint_head h
LEFT JOIN blueprint_site o ON o.blueprint_id=h.blueprint_id
WHERE o.blueprint_id IS NULL ORDER BY h.blueprint_id;
COMMIT;
