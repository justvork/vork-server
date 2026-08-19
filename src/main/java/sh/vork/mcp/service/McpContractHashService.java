package sh.vork.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import sh.vork.mcp.client.dto.McpDiscoverResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class McpContractHashService {

    private final ObjectMapper objectMapper;

    public McpContractHashService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String sha256(McpDiscoverResult discoverResult) {
        try {
            String canonical = objectMapper.writeValueAsString(discoverResult.toCanonicalMap());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hashBytes);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to hash MCP discovery contract", ex);
        }
    }
}
