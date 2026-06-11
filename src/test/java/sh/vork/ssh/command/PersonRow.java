package sh.vork.ssh.command;

import sh.vork.orm.DatabaseEntity;

/**
 * Minimal entity used in command tests.
 */
public record PersonRow(String uuid, String name, int age, boolean active)
        implements DatabaseEntity {}
