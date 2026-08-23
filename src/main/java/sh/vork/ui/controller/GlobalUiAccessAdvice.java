package sh.vork.ui.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import sh.vork.security.Permission;
import sh.vork.surface.service.SurfaceService;

import java.util.List;

/**
 * Exposes common UI authorization flags to all Thymeleaf views.
 */
@ControllerAdvice
public class GlobalUiAccessAdvice {

    private static final String NAV_LAYOUT_SESSION_KEY = "VORK_NAV_LAYOUT";
    private final SurfaceService surfaceService;

    @Autowired
    public GlobalUiAccessAdvice(@Autowired(required = false) SurfaceService surfaceService) {
        this.surfaceService = surfaceService;
    }

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

    @ModelAttribute("navSurfaceApps")
    public List<SurfaceService.SurfaceAppLink> navSurfaceApps(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return List.of();
        }
        if (hasManageUsersPermission() && request != null && !"/".equals(request.getRequestURI())) {
            return List.of();
        }
        if (surfaceService == null) {
            return List.of();
        }
        return surfaceService.listPublishedNavAppsForUser(auth.getName());
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
