package sh.vork.reflection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import sh.vork.orm.DatabaseRepository;

import java.util.List;

/**
 * One-time startup migration for legacy ReflectionBinding ownership.
 *
 * <p>Legacy rows stored a group UUID in the second constructor slot.
 * After the schema change that slot is now reflectionUuid. This migration
 * rewrites legacy rows to point to a concrete reflection UUID.
 */
@Component
public class ReflectionBindingOwnershipMigration {

    private static final Logger log = LoggerFactory.getLogger(ReflectionBindingOwnershipMigration.class);

    private final DatabaseRepository<ReflectionBinding> reflectionBindingRepository;
    private final ReflectionService reflectionService;

    public ReflectionBindingOwnershipMigration(
            DatabaseRepository<ReflectionBinding> reflectionBindingRepository,
            ReflectionService reflectionService) {
        this.reflectionBindingRepository = reflectionBindingRepository;
        this.reflectionService = reflectionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyBindingOwnership() {
        log.debug("ENTER migrateLegacyBindingOwnership");

        int scanned = 0;
        int migrated = 0;
        int alreadyValid = 0;
        int unresolved = 0;

        try (var stream = reflectionBindingRepository.list(0, Integer.MAX_VALUE)) {
            List<ReflectionBinding> bindings = stream.toList();
            for (ReflectionBinding binding : bindings) {
                scanned++;
                if (binding == null || binding.reflectionUuid() == null || binding.reflectionUuid().isBlank()) {
                    unresolved++;
                    continue;
                }

                // Already in new shape: reflectionUuid resolves to a reflection.
                Reflection directReflection = reflectionService.getReflection(binding.reflectionUuid());
                if (directReflection != null) {
                    alreadyValid++;
                    continue;
                }

                // Legacy shape: reflectionUuid field currently stores a group UUID.
                ReflectionGroup legacyGroup = reflectionService.getGroup(binding.reflectionUuid());
                if (legacyGroup == null) {
                    unresolved++;
                    log.warn("Binding ownership migration skipped: unresolved ownership token [bindingUuid={}, token={}]",
                            binding.uuid(), binding.reflectionUuid());
                    continue;
                }

                List<Reflection> candidates = reflectionService.reflectionsForGroup(legacyGroup.uuid());
                if (candidates.isEmpty()) {
                    unresolved++;
                    log.warn("Binding ownership migration skipped: group has no reflections [bindingUuid={}, groupUuid={}]",
                            binding.uuid(), legacyGroup.uuid());
                    continue;
                }

                Reflection target = candidates.getFirst();
                ReflectionBinding migratedBinding = new ReflectionBinding(
                        binding.uuid(),
                        target.uuid(),
                        binding.name(),
                        binding.baseUrl(),
                        binding.parameterValues(),
                        binding.version() + 1,
                        binding.createdAt(),
                        System.currentTimeMillis());
                reflectionBindingRepository.save(migratedBinding);
                migrated++;

                log.info("Migrated reflection binding ownership [bindingUuid={}, fromGroupUuid={}, toReflectionUuid={}]",
                        binding.uuid(), legacyGroup.uuid(), target.uuid());
            }
        }

        log.info("Reflection binding ownership migration complete [scanned={}, migrated={}, alreadyValid={}, unresolved={}]",
                scanned, migrated, alreadyValid, unresolved);
        log.debug("EXIT migrateLegacyBindingOwnership");
    }
}
