package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code compileJavaType} function tool.
 *
 * <p>The model supplies complete Java source code for a single compilation unit.
 * The target package is controlled by {@code group}. Dots are allowed in
 * group names (for example {@code jadaptive.crm}).
 */
public record CompileTypeRequest(
        @JsonProperty(value = "group")
        @JsonPropertyDescription(
                "Logical record group (for example: sales, billing, support). " +
                "When provided, generated code will be compiled under package <group>. " +
                "Dots are allowed in group names. group must not be under sh.vork.*. " +
                "If missing, the tool will ask for confirmation before compiling.")
        String group,

        @JsonProperty(required = true, value = "source")
        @JsonPropertyDescription(
                """
Complete Java source code for a single compilation unit, including a package declaration.
The package declaration will be rewritten to the confirmed {@code group} package.
Supports record, class, interface, and enum declarations.
If the user asks to create a record or enum, this schema should be used.
         """)
        String source
) {}
