package sh.vork.reflection;

/**
 * Reflection transport type.
 *
 * <p>Only {@link #REST} is currently executable. OAuth is configured via
 * {@link ReflectionAuthenticationMode} on REST groups.
 */
public enum ReflectionType {
    REST,
    RECORD,
    MONGO
}
