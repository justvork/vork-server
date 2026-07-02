package sh.vork.typegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a built-in application type as safe to export through the generic type export tools.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExportableType {

    /**
     * Optional human-readable description surfaced by discovery/export tooling.
     */
    String description() default "";
}
