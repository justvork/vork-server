package sh.vork.reflection;

import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import sh.vork.security.Permission;

/**
 * Serves the top-level Reflections management page.
 */
@Controller
public class ReflectionPageController {

    @GetMapping("/reflections")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public String reflectionsPage(Model model) {
        model.addAttribute("canManageUsers", hasAuthority(Permission.USERS_MANAGE.authority()));
        return "reflections";
    }

    private static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
