package sh.vork.ui.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.ui.Model;

@Controller
public class ChatPageController {

    @GetMapping("/")
    public String homePage(Model model) {
        model.addAttribute("isHomePage", true);
        return "home";
    }

    @GetMapping({"/chat", "/index.html"})
    public String chatPage(Model model) {
        model.addAttribute("isChatPage", true);
        return "index";
    }

    @GetMapping("/agent/{agentTemplateId}")
    public String agentPromptPage(@PathVariable String agentTemplateId, Model model) {
        model.addAttribute("agentTemplateId", agentTemplateId);
        return "agent-prompt";
    }
}
