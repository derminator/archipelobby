package com.github.derminator.archipelobby.user

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import reactor.test.StepVerifier

@SpringBootTest
@ActiveProfiles("test")
class UserServiceTests {

    @Autowired
    lateinit var userService: UserService

    @Autowired
    lateinit var userRepository: UserRepository

    @Test
    fun `test first admin promotion`() {
        userRepository.deleteAll().block()

        userService.hasAnyAdmin()
            .test()
            .expectNext(false)
            .verifyComplete()

        userService.setFirstAdmin("123", "adminUser")
            .test()
            .expectNextMatches { it.role == UserRole.ADMIN && it.status == UserStatus.APPROVED }
            .verifyComplete()

        userService.hasAnyAdmin()
            .test()
            .expectNext(true)
            .verifyComplete()
            
        userService.setFirstAdmin("456", "otherUser")
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()
    }

    @Test
    fun `test user registration and approval`() {
        userRepository.deleteAll().block()
        userService.setFirstAdmin("123", "adminUser").block()

        userService.findOrCreateUser("456", "newUser")
            .test()
            .expectNextMatches { it.status == UserStatus.PENDING }
            .verifyComplete()

        userService.getPendingRequests()
            .test()
            .expectNextCount(1)
            .verifyComplete()

        userService.approveUser("456", UserRole.USER)
            .test()
            .expectNextMatches { it.status == UserStatus.APPROVED && it.role == UserRole.USER }
            .verifyComplete()

        userService.getPendingRequests()
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }
    
    private fun <T : Any> reactor.core.publisher.Mono<T>.test() = StepVerifier.create(this)
    private fun <T : Any> reactor.core.publisher.Flux<T>.test() = StepVerifier.create(this)
}
