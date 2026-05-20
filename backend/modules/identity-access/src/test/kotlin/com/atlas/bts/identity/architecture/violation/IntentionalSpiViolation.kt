// [TDD RED용 임시 파일] ArchUnit 룰 위반 예시 — GREEN 단계에서 삭제됨

package com.atlas.bts.identity.architecture.violation

import org.springframework.web.bind.annotation.GetMapping

/**
 * ArchUnit SpiBoundary 룰 T5 RED 확인용 의도적 위반 클래스.
 * `spi` 패키지 내 Spring Framework 의존 금지 룰을 위반.
 * GREEN 단계에서 이 파일을 삭제하면 룰이 통과된다.
 *
 * 주의: 이 클래스는 spi 패키지에 있지 않고 architecture.violation 패키지에 있다.
 * ArchUnit 테스트에서 spi 패키지 위반을 시뮬레이션하기 위해
 * 테스트 자체에서 violation 패키지를 `..spi..` 로 alias해서 사용하거나,
 * 아니면 실제로 spi 패키지에 위반 클래스를 만들어야 한다.
 */
@Suppress("unused")
class IntentionalSpiViolation {
    // Spring Web 의존 — spi 패키지에 있다면 룰 위반
    @GetMapping("/violation")
    fun violate(): String = "violation"
}
