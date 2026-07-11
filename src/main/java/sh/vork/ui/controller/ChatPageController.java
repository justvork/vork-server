package sh.vork.ui.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.ui.Model;

@Controller
public class ChatPageController {

    @GetMapping({"/", "/index.html"})
    public String chatPage(Model model) {
        model.addAttribute("isChatPage", true);
        return "index";
    }
}
