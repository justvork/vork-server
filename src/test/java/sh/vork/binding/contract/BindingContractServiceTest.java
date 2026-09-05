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

    @Test
    void createContractAllowsEmptyToolsForGroupFirstFlow() {
        BindingContract request = new BindingContract(
                null,
                "Empty Group",
                "desc",
                List.of(),
                "binding",
                "emptygroup",
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                0L,
                0L);

        BindingContract created = service.createContract(request);
        assertNotNull(created);
        assertEquals("binding-emptygroup-SNAPSHOT", created.uuid());
        assertEquals(0, created.tools().size());
    }

    @Test
    void addUpdateDeleteToolPersistsImmediately() {
        BindingContract created = service.createContract(new BindingContract(
                null,
                "Empty Group",
                "desc",
                List.of(),
                "binding",
                "toolflow",
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                0L,
                0L));

        BindingContractToolDefinition first = new BindingContractToolDefinition(
                "classifyEmail",
                "Classify sender",
                List.of(new ReflectionInputParameter("sender", "string", "sender", true, false)));
        BindingContract afterAdd = service.addTool(created.uuid(), first);
        assertEquals(1, afterAdd.tools().size());
        assertEquals("classifyEmail", afterAdd.tools().getFirst().name());

        BindingContractToolDefinition renamed = new BindingContractToolDefinition(
                "classifyInbox",
                "Classify inbox",
                List.of(new ReflectionInputParameter("subject", "string", "subject", true, false)));
        BindingContract afterUpdate = service.updateTool(created.uuid(), "classifyEmail", renamed);
        assertEquals(1, afterUpdate.tools().size());
        assertEquals("classifyInbox", afterUpdate.tools().getFirst().name());
        assertEquals("subject", afterUpdate.tools().getFirst().inputParameters().getFirst().name());

        BindingContract afterDelete = service.deleteTool(created.uuid(), "classifyInbox");
        assertEquals(0, afterDelete.tools().size());
    }

    @Test
    void addToolRejectedWhenContractNotMutable() {
        BindingContract created = service.createContract(sampleContract(null));
        service.markSubmitted(created.uuid());

        BindingContractToolDefinition tool = new BindingContractToolDefinition(
                "newTool",
                "desc",
                List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addTool(created.uuid(), tool));
        assertTrue(ex.getMessage().contains("Only SNAPSHOT or REJECTED"));
    }

    @Test
    void updateToolRejectsDuplicateName() {
        BindingContract created = service.createContract(new BindingContract(
                null,
                "Tool Group",
                "desc",
                List.of(),
                "binding",
                "duplicatecheck",
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                0L,
                0L));

        service.addTool(created.uuid(), new BindingContractToolDefinition("a", "", List.of()));
        service.addTool(created.uuid(), new BindingContractToolDefinition("b", "", List.of()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateTool(created.uuid(), "a", new BindingContractToolDefinition("b", "", List.of())));
        assertTrue(ex.getMessage().contains("Tool names must be unique"));
    }

    @Test
    void createContractPreservesPrivateToolVisibilityFlag() {
        BindingContract request = new BindingContract(
                null,
                "Visibility Contract",
                "desc",
                List.of(new BindingContractToolDefinition(
                        "internalRoute",
                        "Internal only",
                        List.of(new ReflectionInputParameter("id", "string", "identifier", true, false)),
                        false)),
                "binding",
                "visibility",
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                0L,
                0L);

        BindingContract created = service.createContract(request);
        assertEquals(1, created.tools().size());
        assertEquals(Boolean.FALSE, created.tools().getFirst().publiclyVisible());
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
