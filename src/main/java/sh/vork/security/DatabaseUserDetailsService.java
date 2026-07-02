package sh.vork.security;

import java.util.ArrayList;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import sh.vork.orm.DatabaseRepository;

/**
 * Database-backed UserDetailsService.
 * Loads user credentials from the MongoDB VorkUser collection.
 *
 * <p>Account creation is handled by the first-run setup wizard ({@code SetupController}).
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final DatabaseRepository<VorkUser> userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public DatabaseUserDetailsService(DatabaseRepository<VorkUser> userRepository,
                                     PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        VorkUser vorkUser = userRepository.get(username);
        if (vorkUser == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        UserRole role = UserRole.fromStoredValue(vorkUser.role());
        var authorities = new ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority(role.authority()));
        RolePermissionPolicy.permissionsFor(role)
                .forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission.authority())));

        return User.withUsername(vorkUser.uuid())
                .password(vorkUser.passwordHash())
                .authorities(authorities)
                .disabled(!vorkUser.isEnabled())
                .build();
    }

    /**
     * Public method to update a user's password.
     * Called by ChangePasswordController.
     * Returns true if update succeeded.
     */
    public boolean updatePassword(String username, String newPassword) {
        VorkUser user = userRepository.get(username);
        if (user == null) {
            return false;
        }

        VorkUser updated = new VorkUser(
            user.uuid(),
            passwordEncoder.encode(newPassword),
            user.role(),
            user.enabled(),
            user.createdAt(),
            System.currentTimeMillis()
        );
        userRepository.save(updated);
        return true;
    }
}
