package com.github.derminator.archipelobby

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class GuideController {
    @GetMapping("/guide")
    fun guide(): String = "guide"
}
