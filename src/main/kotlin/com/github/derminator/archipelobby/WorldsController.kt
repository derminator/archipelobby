package com.github.derminator.archipelobby

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@Controller
@RequestMapping("/worlds")
class WorldsController(private val storageService: StorageService) {

    @GetMapping
    fun listWorlds(model: Model): String {
        model.addAttribute("worldFiles", storageService.listWorlds())
        return "worlds"
    }

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadWorld(
        @RequestPart("file") file: org.springframework.http.codec.multipart.FilePart,
        @AuthenticationPrincipal principal: OAuth2User?
    ) = mono {
        if (principal == null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val dataBuffer = DataBufferUtils.join(file.content()).awaitSingle()
        dataBuffer.asInputStream(true).use { input ->
            storageService.storeWorld(file.filename(), input)
        }
        "redirect:/worlds"
    }

    @GetMapping("/files/{filename}")
    fun downloadWorld(@PathVariable filename: String): ResponseEntity<org.springframework.core.io.FileSystemResource> {
        val resource = storageService.loadWorld(filename)
        val headers = HttpHeaders()
        headers.contentDisposition = ContentDisposition.attachment().filename(resource.filename ?: filename).build()
        headers.contentType = MediaType.APPLICATION_OCTET_STREAM
        return ResponseEntity.ok().headers(headers).body(resource)
    }
}
