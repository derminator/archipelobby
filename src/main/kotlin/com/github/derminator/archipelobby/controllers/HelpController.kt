package com.github.derminator.archipelobby.controllers

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/help")
class HelpController {

    @GetMapping
    fun getHelp(): String {
        return "help/index"
    }

    @GetMapping("/setup")
    fun getSetup(): String {
        return "help/setup"
    }

    @GetMapping("/glossary")
    fun getGlossary(): String {
        return "help/glossary"
    }

    @GetMapping("/tools")
    fun getTools(): String {
        return "help/tools"
    }
}