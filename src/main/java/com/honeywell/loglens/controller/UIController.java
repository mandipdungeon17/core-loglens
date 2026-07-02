package com.honeywell.loglens.controller;

import com.honeywell.loglens.config.LogLensConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class UIController {

  private final LogLensConfig config;

  @GetMapping("/")
  public String index(Model model) {
    model.addAttribute("services", config.getServices());
    model.addAttribute("showMetrics", config.isShowMetrics());
    return "index";
  }
}
