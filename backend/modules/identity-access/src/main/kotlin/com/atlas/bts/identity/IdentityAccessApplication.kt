// identity-access BC 스프링 부트 진입점 — 비즈니스 로직 없는 부트스트랩 클래스

package com.atlas.bts.identity

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class IdentityAccessApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<IdentityAccessApplication>(*args)
}
