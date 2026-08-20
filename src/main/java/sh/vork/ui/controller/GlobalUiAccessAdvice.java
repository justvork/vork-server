package sh.vork.ui.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import sh.vork.security.Permission;

/**
 * Exposes common UI authorization flags to all Thymeleaf views.
 */
@ControllerAdvice
public class GlobalUiAccessAdvice {

    private static final String NAV_LAYOUT_SESSION_KEY = "VORK_NAV_LAYOUT";

    @ModelAttribute("canManageUsers")
    public boolean canManageUsers() {
        return hasManageUsersPermission();
    }

    @ModelAttribute("navAdminView")
    public boolean navAdminView(HttpServletRequest request, HttpSession session) {
        if (!hasManageUsersPermission()) {
            return false;
        }
        if (request != null && "/".equals(request.getRequestURI())) {
            return false;
        }
        Object mode = session == null ? null : session.getAttribute(NAV_LAYOUT_SESSION_KEY);
        return mode == null || !"USER".equalsIgnoreCase(String.valueOf(mode));
    }

    private static boolean hasManageUsersPermission() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> Permission.USERS_MANAGE.authority().equals(a.getAuthority()));
    }
}
