// [TDD RED용 임시 파일] ArchUnit spi 패키지 Spring 의존 위반 예시 — GREEN 단계에서 삭제됨

package com.atlas.bts.identity.spi

import org.springframework.web.bind.annotation.GetMapping

/**
 * T5 RED 확인용 의도적 위반 클래스.
 * spi 패키지에서 Spring Web(@GetMapping)을 import하므로 SpiBoundary 룰 1을 위반한다.
 * GREEN 단계에서 삭제.
 */
@Suppress("unused")
class ViolationForArchUnitRed {
    @GetMapping("/intentional-violation")
    fun violate(): String = "violation"
}
