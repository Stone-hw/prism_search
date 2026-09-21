package com.prismsearch.web;

import com.prismsearch.provider.SearchProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Serves the Thymeleaf-rendered search page.
 */
@Controller
public class PageController {

    private final List<SearchProvider> providers;

    public PageController(List<SearchProvider> providers) {
        this.providers = providers;
    }

    @GetMapping({"/", "/index"})
    public String index(Model model) {
        List<String> enabled = providers.stream()
                .filter(SearchProvider::enabled)
                .map(SearchProvider::name)
                .toList();
        model.addAttribute("enabledProviders", enabled);
        model.addAttribute("allProviders", providers.stream().map(SearchProvider::name).toList());
        model.addAttribute("version", "0.1.0");
        return "index";
    }
}
