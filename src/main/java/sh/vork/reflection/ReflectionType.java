package sh.vork.reflection;

/**
 * Reflection transport type.
 *
 * <p>Only {@link #REST} is currently executable. {@link #OAUTH} and {@link #MONGO}
 * are placeholders for future support.
 */
public enum ReflectionType {
    REST,
    OAUTH,
    MONGO
}
