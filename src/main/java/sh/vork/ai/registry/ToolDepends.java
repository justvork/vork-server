package sh.vork.ai.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares tool-bean dependencies for a tool {@code @Bean} factory method.
 *
 * <p>Each value is a Spring bean/tool ID that should be auto-included whenever
 * the annotated tool is attached to an agent/session/skill. This helps avoid
 * missing companion tools in template configuration.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolDepends {
    String[] value() default {};
}
