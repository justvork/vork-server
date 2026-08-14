package sh.vork.hub;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HubPageController {

    @GetMapping("/hub")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public String hubPage() {
        return "hub";
    }
}
