package sh.vork.binding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-in marker for DatabaseEntity types that should be exposed as record bindings.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GenerateBinding {

    /**
     * When false, explicitly suppresses binding exposure even if the annotation is present.
     */
    boolean value() default true;
}
