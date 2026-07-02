package sh.vork.security.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sh.vork.security.UserManagementService;
import sh.vork.security.VorkUser;

import java.util.Map;

@RestController
public class UserManagementController {

    private static final Logger log = LoggerFactory.getLogger(UserManagementController.class);

    private final UserManagementService userManagementService;

    public UserManagementController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping("/api/users")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> listUsers() {
        log.debug("ENTER listUsers");
        return ResponseEntity.ok(userManagementService.listUsers());
    }

    @PostMapping("/api/users")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest request) {
        log.debug("ENTER createUser: username={}, role={}", request.username(), request.role());
        try {
            VorkUser created = userManagementService.createUser(request.username(), request.password(), request.role());
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "username", created.uuid(),
                    "role", created.role(),
                    "enabled", created.isEnabled()));
        } catch (IllegalArgumentException ex) {
            log.warn("createUser rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PutMapping("/api/users/{username}/role")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateRole(@PathVariable String username, @RequestBody UpdateRoleRequest request) {
        log.debug("ENTER updateRole: username={}, role={}", username, request.role());
        try {
            VorkUser updated = userManagementService.updateRole(username, request.role());
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "username", updated.uuid(),
                    "role", updated.role(),
                    "enabled", updated.isEnabled()));
        } catch (IllegalArgumentException ex) {
            log.warn("updateRole rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PutMapping("/api/users/{username}/enabled")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> setEnabled(@PathVariable String username, @RequestBody SetEnabledRequest request) {
        log.debug("ENTER setEnabled: username={}, enabled={}", username, request.enabled());
        try {
            VorkUser updated = userManagementService.setEnabled(username, request.enabled());
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "username", updated.uuid(),
                    "role", updated.role(),
                    "enabled", updated.isEnabled()));
        } catch (IllegalArgumentException ex) {
            log.warn("setEnabled rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PutMapping("/api/users/{username}/password/reset")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> resetPassword(@PathVariable String username, @RequestBody ResetPasswordRequest request) {
        log.debug("ENTER resetPassword: username={}", username);
        try {
            userManagementService.resetPassword(username, request.newPassword());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException ex) {
            log.warn("resetPassword rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @DeleteMapping("/api/users/{username}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deleteUser(@PathVariable String username) {
        log.debug("ENTER deleteUser: username={}", username);
        try {
            userManagementService.deleteUser(username);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException ex) {
            log.warn("deleteUser rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    record CreateUserRequest(String username, String password, String role) {}
    record UpdateRoleRequest(String role) {}
    record SetEnabledRequest(boolean enabled) {}
    record ResetPasswordRequest(String newPassword) {}
}
