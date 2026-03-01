package com.github.derminator.archipelobby

import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.config.PathMatchConfigurer
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
class WebFluxConfiguration : WebFluxConfigurer {
    override fun configurePathMatching(configurer: PathMatchConfigurer) {
        configurer.setUseTrailingSlashMatch(true)
    }
}
