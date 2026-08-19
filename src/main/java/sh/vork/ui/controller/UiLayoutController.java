package sh.vork.ui.controller;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import sh.vork.security.Permission;

@Controller
@PreAuthorize("isAuthenticated()")
public class UiLayoutController {

    private static final String NAV_LAYOUT_SESSION_KEY = "VORK_NAV_LAYOUT";

    @GetMapping("/ui/layout/{mode}")
    public String switchLayout(@PathVariable String mode,
                               HttpServletRequest request,
                               HttpSession session) {
        boolean canManageUsers = SecurityContextHolder.getContext().getAuthentication() != null
            && SecurityContextHolder.getContext().getAuthentication().getAuthorities() != null
            && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(a -> Permission.USERS_MANAGE.authority().equals(a.getAuthority()));
        if (canManageUsers) {
            if ("user".equalsIgnoreCase(mode)) {
                session.setAttribute(NAV_LAYOUT_SESSION_KEY, "USER");
            } else if ("admin".equalsIgnoreCase(mode)) {
                session.setAttribute(NAV_LAYOUT_SESSION_KEY, "ADMIN");
            }
        }

        String referer = request.getHeader("Referer");
        String safeRedirect = extractSafeLocalRedirect(referer);
        if (safeRedirect == null || safeRedirect.isBlank() || safeRedirect.startsWith("/ui/layout/")) {
            safeRedirect = "/";
        }
        return "redirect:" + safeRedirect;
    }

    private static String extractSafeLocalRedirect(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(referer);
            String path = uri.getPath();
            if (path == null || path.isBlank() || !path.startsWith("/")) {
                return null;
            }
            String query = uri.getRawQuery();
            return query == null || query.isBlank() ? path : (path + "?" + query);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
