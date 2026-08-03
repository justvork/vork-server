package sh.vork.ui.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import sh.vork.security.Permission;

/**
 * Exposes common UI authorization flags to all Thymeleaf views.
 */
@ControllerAdvice
public class GlobalUiAccessAdvice {

    @ModelAttribute("canManageUsers")
    public boolean canManageUsers() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> Permission.USERS_MANAGE.authority().equals(a.getAuthority()));
    }
}
