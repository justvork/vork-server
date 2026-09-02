package sh.vork.binding.contract;

import sh.vork.artifact.ArtifactStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.reflection.ReflectionInputParameter;

@ExtendWith(MockitoExtension.class)
class BindingContractServiceTest {

    @Mock
    private RepositoryFactory repositoryFactory;

    private BindingContractService service;

    @BeforeEach
    void setUp() {
        MapDatabaseRepository<BindingContract> repo = new MapDatabaseRepository<>(BindingContract.class);
        when(repositoryFactory.create(BindingContract.class)).thenReturn(repo);
        service = new BindingContractService(repositoryFactory.create(BindingContract.class));
    }

    @Test
    void createContractPersistsDeterministicVid() {
        BindingContract created = service.createContract(sampleContract(null));

        assertEquals("binding-emailtriage-SNAPSHOT", created.uuid());
        assertEquals(ArtifactStatus.SNAPSHOT, created.artifactStatus());
        assertEquals(1, created.tools().size());
        assertEquals("sender", created.tools().getFirst().inputParameters().getFirst().name());
    }

    @Test
    void updateRejectsIdentityChange() {
        BindingContract created = service.createContract(sampleContract(null));

        BindingContract changedIdentity = new BindingContract(
                created.uuid(),
                "Email Triage Contract",
                "desc",
                created.tools(),
                "binding",
                "different",
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                created.createdAt(),
                created.updatedAt());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateContract(created.uuid(), changedIdentity));
        assertTrue(ex.getMessage().contains("immutable"));
    }

    @Test
    void importRejectsUuidMismatchAgainstVid() {
        BindingContractService.BindingContractExportPackage pkg =
                new BindingContractService.BindingContractExportPackage(
                        "vorkBindingContractExport",
                        1,
                        sampleContract("legacy-random-id"));

        BindingContractService.BindingContractImportResult result = service.importContract(pkg);
        assertEquals("error", result.status());
        assertTrue(result.message().contains("deterministic VID"));
    }

    @Test
    void lifecycleTransitionsFollowSnapshotToPublishedPath() {
        BindingContract created = service.createContract(sampleContract(null));

        BindingContract submitted = service.markSubmitted(created.uuid());
        assertNotNull(submitted);
        assertEquals(ArtifactStatus.SUBMITTED, submitted.artifactStatus());

        BindingContract staged = service.markStaged(created.uuid());
        assertNotNull(staged);
        assertEquals(ArtifactStatus.STAGED, staged.artifactStatus());

        BindingContract published = service.markPublished(created.uuid());
        assertNotNull(published);
        assertEquals(ArtifactStatus.PUBLISHED, published.artifactStatus());
    }

    @Test
    void unsupportedInputTypeIsRejected() {
        BindingContractToolDefinition tool = new BindingContractToolDefinition(
                "classifyEmail",
                "desc",
                List.of(new ReflectionInputParameter("sender", "uuid", "bad type", true, false)));

        BindingContract request = new BindingContract(
                null,
                "Email Triage Contract",
                "desc",
                List.of(tool),
                "binding",
                "emailtriage",
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                0L,
                0L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createContract(request));
        assertTrue(ex.getMessage().contains("Unsupported input parameter type"));
    }

    @Test
    void acceptsDateAndTimestampInputTypes() {
        BindingContractToolDefinition tool = new BindingContractToolDefinition(
                "schedule",
                "desc",
                List.of(
                        new ReflectionInputParameter("runDate", "date", "Run date", true, false),
                        new ReflectionInputParameter("runAt", "timestamp", "Run timestamp", true, false)));

        BindingContract request = new BindingContract(
                null,
                "Scheduler Contract",
                "desc",
                List.of(tool),
                "binding",
                "scheduler",
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                0L,
                0L);

        BindingContract created = service.createContract(request);

        assertNotNull(created);
        assertEquals("date", created.tools().getFirst().inputParameters().get(0).type());
        assertEquals("timestamp", created.tools().getFirst().inputParameters().get(1).type());
    }

    private static BindingContract sampleContract(String uuid) {
        BindingContractToolDefinition tool = new BindingContractToolDefinition(
                "classifyEmail",
                "Classify sender and subject",
                List.of(
                        new ReflectionInputParameter("sender", "string", "sender address", true, false),
                        new ReflectionInputParameter("subject", "string", "subject line", true, false),
                        new ReflectionInputParameter("labels", "string", "labels", false, true)
                ));
        return new BindingContract(
                uuid,
                "Email Triage Contract",
                "Maps inbox messages to a normalized classification schema.",
                List.of(tool),
                "binding",
                "emailtriage",
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                0L,
                0L);
    }
}
