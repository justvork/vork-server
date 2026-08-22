package sh.vork.typegen;

import sh.vork.orm.DatabaseEntity;

/**
 * Ownership mapping for record instances created through RECORD reflections.
 */
public record TypeRecordBindingScope(
        String uuid,
        String typeFqn,
        String recordUuid,
        String bindingUuid,
        String bindingName,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public TypeRecordBindingScope {
        if (uuid == null) {
            uuid = "";
        }
        if (typeFqn == null) {
            typeFqn = "";
        }
        if (recordUuid == null) {
            recordUuid = "";
        }
        if (bindingUuid == null) {
            bindingUuid = "";
        }
        if (bindingName == null) {
            bindingName = "";
        }
    }
}
