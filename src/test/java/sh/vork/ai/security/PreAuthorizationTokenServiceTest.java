package sh.vork.ai.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PreAuthorizationTokenServiceTest {

    private DatabaseRepository<PreAuthorizationTokenRecord> repo;
    private PreAuthorizationTokenService service;
    private final Map<String, PreAuthorizationTokenRecord> store = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        repo = mock(DatabaseRepository.class);
        RepositoryFactory factory = mock(RepositoryFactory.class);
        when(factory.create(PreAuthorizationTokenRecord.class)).thenReturn(repo);
        when(repo.get(any(String.class))).thenAnswer(inv -> store.get(inv.getArgument(0)));
        doAnswer(inv -> {
            PreAuthorizationTokenRecord record = inv.getArgument(0);
            store.put(record.uuid(), record);
            return null;
        }).when(repo).save(any(PreAuthorizationTokenRecord.class));
        when(repo.search(eq(0), eq(Integer.MAX_VALUE), eq("createdAt"), eq(SortOrder.ASC), any(SearchQuery.class), any(SearchQuery.class), any(SearchQuery.class), any(SearchQuery.class)))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArguments();
                    SearchQuery[] queries = new SearchQuery[args.length - 4];
                    for (int i = 4; i < args.length; i++) {
                        queries[i - 4] = (SearchQuery) args[i];
                    }
                    Stream<PreAuthorizationTokenRecord> stream = store.values().stream().filter(record -> {
                        Map<String, Object> map = Map.of(
                                "status", record.status(),
                                "username", record.username(),
                                "toolName", record.toolName(),
                                "argumentsSha256", record.argumentsSha256());
                        for (SearchQuery query : queries) {
                            if (!query.test(map)) {
                                return false;
                            }
                        }
                        return true;
                    });
                    return stream;
                });
        service = new PreAuthorizationTokenService(factory);
    }

    @Test
    void canonicalizeArguments_sortsObjectKeys() {
        String first = service.canonicalizeArguments("{\"b\":2,\"a\":1}");
        String second = service.canonicalizeArguments("{\"a\":1,\"b\":2}");
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
    }

    @Test
    void consumeMatchingToken_requiresExactDigestAndConsumesOnce() {
        PreAuthorizationTokenService.IssuedToken issued = service.issueToken(
                "alice",
                "session-1",
                "compileJavaType",
                "{\"source\":\"class A {}\"}",
                900,
                "SESSION",
                "compile class");
        assertNotNull(issued.token());

        assertTrue(service.consumeMatchingToken("alice", "session-1", "compileJavaType", "{\"source\":\"class A {}\"}"));
        assertFalse(service.consumeMatchingToken("alice", "session-1", "compileJavaType", "{\"source\":\"class A {}\"}"));
    }

    @Test
    void consumeMatchingToken_rejectsDifferentArguments() {
        service.issueToken("alice", "session-1", "compileJavaType", "{\"source\":\"class A {}\"}", 900, "SESSION", "compile class");
        assertFalse(service.consumeMatchingToken("alice", "session-1", "compileJavaType", "{\"source\":\"class B {}\"}"));
    }

    @Test
    void consumeMatchingToken_respectsSessionScope() {
        service.issueToken("alice", "session-1", "compileJavaType", "{\"source\":\"class A {}\"}", 900, "SESSION", "compile class");
        assertFalse(service.consumeMatchingToken("alice", "session-2", "compileJavaType", "{\"source\":\"class A {}\"}"));
    }

    @Test
    void consumeMatchingToken_allowsBackgroundScopeAcrossSessions() {
        service.issueToken("alice", "session-1", "compileJavaType", "{\"source\":\"class A {}\"}", 900, "BACKGROUND", "compile class");
        assertTrue(service.consumeMatchingToken("alice", "session-2", "compileJavaType", "{\"source\":\"class A {}\"}"));
    }
}