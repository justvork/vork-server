package sh.vork.generated;

import sh.vork.binding.GenerateBinding;
import sh.vork.orm.DatabaseEntity;

@GenerateBinding
public record TestBoundEntity(String uuid, String name) implements DatabaseEntity {
}
