package sh.vork.ui.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import sh.vork.oauth.OAuthClientService;
import sh.vork.ai.provider.AiModelService;
import sh.vork.ai.registry.ToolDescriptor;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.security.Permission;
import sh.vork.security.UserManagementService;
import sh.vork.setup.SystemSettings;
import sh.vork.setup.SystemSettingsService;
import sh.vork.ui.SettingsPage;
import sh.vork.ui.SettingsPageRegistry;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/settings")
public class SettingsController {
    private final SettingsPageRegistry registry;
    private final ToolRegistry toolRegistry;
    private final AiModelService modelService;
    private final SystemSettingsService systemSettingsService;
    private final OAuthClientService oAuthClientService;
    private final UserManagementService userManagementService;

    @Autowired
    public SettingsController(SettingsPageRegistry registry, ToolRegistry toolRegistry,
                              AiModelService modelService,
                              SystemSettingsService systemSettingsService,
                              OAuthClientService oAuthClientService,
                              UserManagementService userManagementService) {
        this.registry = registry;
        this.toolRegistry = toolRegistry;
        this.modelService = modelService;
        this.systemSettingsService = systemSettingsService;
        this.oAuthClientService = oAuthClientService;
        this.userManagementService = userManagementService;
    }

    @GetMapping("")
    public String settingsHome(Model model) {
        List<SettingsPage> pages = registry.getAllPages();
        if (!hasAuthority(Permission.USERS_MANAGE.authority())) {
            pages = pages.stream()
                    .filter(page -> !"users".equals(page.getPath()))
                    .toList();
        }
        model.addAttribute("pages", pages);
        return "settings";
    }

    @GetMapping("/tool-inspector")
    public String toolInspector(Model model) {
        Map<String, List<ToolDescriptor>> toolsByCategory = toolRegistry.getToolsByCategory();
        int toolCount = toolsByCategory.values().stream().mapToInt(List::size).sum();
        model.addAttribute("toolsByCategory", toolsByCategory);
        model.addAttribute("toolCount", toolCount);
        return "settings/tool-inspector";
    }

    @GetMapping("/ai-models")
    public String aiModels(Model model) {
        model.addAttribute("providers", modelService.getAllProviders());
        SystemSettings gs = systemSettingsService.getGlobal();
        String globalKey = (gs != null && gs.defaultProvider() != null && gs.defaultModelId() != null)
                ? gs.defaultProvider() + ":" + gs.defaultModelId() : "";
        model.addAttribute("globalDefaultKey", globalKey);
        return "settings/ai-models";
    }

    @GetMapping("/oauth-clients")
    public String oauthClients(Model model) {
        String username = resolveUsername();
        model.addAttribute("oauthClients", oAuthClientService.listConfiguredClients(username));
        model.addAttribute("canManageUsers", hasAuthority(Permission.USERS_MANAGE.authority()));
        return "settings/oauth-clients";
    }

    @PostMapping("/oauth-clients/{clientUuid}/delete")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public String deleteOAuthClient(@PathVariable String clientUuid, RedirectAttributes redirectAttributes) {
        boolean deleted = oAuthClientService.deleteClientByUuidAsAdmin(clientUuid);
        if (deleted) {
            redirectAttributes.addFlashAttribute("oauthClientDeleteMessage", "OAuth client deleted.");
        } else {
            redirectAttributes.addFlashAttribute("oauthClientDeleteError", "OAuth client was not found.");
        }
        return "redirect:/settings/oauth-clients";
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public String users(Model model) {
        model.addAttribute("users", userManagementService.listUsers());
        return "settings/users";
    }

    @GetMapping("/knowledge")
    public String knowledge() {
        return "settings/knowledge";
    }

    @GetMapping("/secrets")
    public String secrets() {
        return "settings/secrets";
    }

    @GetMapping("/{page}")
    public String settingsPage(@org.springframework.web.bind.annotation.PathVariable String page) {
        return "settings/" + page;
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

    private static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
