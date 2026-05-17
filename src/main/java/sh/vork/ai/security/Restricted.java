package sh.vork.ai.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring {@code @Bean} factory method that produces a {@link org.springframework.ai.tool.ToolCallback}
 * as requiring user authorization before the AI is permitted to invoke it.
 *
 * <p>{@link AuthorizationRuleEngine} scans all {@code ToolCallback} bean definitions at startup.
 * Any tool whose factory method carries this annotation is added to the engine's restricted set,
 * meaning a call to
 * {@link AuthorizationRuleEngine#requiresAuthorization(String, String, String)} will return
 * {@code true} unless an active exception rule grants the requesting user access.
 *
 * <p>Usage:
 * <pre>{@code
 * @Bean
 * @Restricted
 * public ToolCallback compileJavaType(TypeGeneratorService service) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Restricted {
}
