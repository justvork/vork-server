package sh.vork.typegen;

import sh.vork.orm.DatabaseEntity;

/**
 * Sidecar metadata for record instances tracking schema version and entity revision.
 */
public record TypeRecordVersionMetadata(
        String uuid,
        String typeFqn,
        String recordUuid,
        long schemaVersion,
        long entityRevision,
        String createdByBindingUuid,
        String updatedByBindingUuid,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public TypeRecordVersionMetadata {
        if (uuid == null) {
            uuid = "";
        }
        if (typeFqn == null) {
            typeFqn = "";
        }
        if (recordUuid == null) {
            recordUuid = "";
        }
        if (createdByBindingUuid == null) {
            createdByBindingUuid = "";
        }
        if (updatedByBindingUuid == null) {
            updatedByBindingUuid = "";
        }
        if (schemaVersion < 0) {
            schemaVersion = 0;
        }
        if (entityRevision < 1) {
            entityRevision = 1;
        }
    }
}