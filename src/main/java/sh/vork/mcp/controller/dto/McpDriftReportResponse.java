package sh.vork.mcp.controller.dto;

import sh.vork.mcp.service.McpContractDiffService;

public record McpDriftReportResponse(
        String bindingUuid,
        String previousHash,
        String currentHash,
        boolean drifted,
        McpContractDiffService.McpContractDiffSection tools,
        McpContractDiffService.McpContractDiffSection resources,
        McpContractDiffService.McpContractDiffSection prompts
) {
    public static McpDriftReportResponse of(String bindingUuid,
                                            String previousHash,
                                            String currentHash,
                                            McpContractDiffService.McpContractDiff diff) {
        return new McpDriftReportResponse(
                bindingUuid,
                previousHash,
                currentHash,
                diff.drifted(),
                diff.tools(),
                diff.resources(),
                diff.prompts());
    }
}
