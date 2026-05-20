// 사용자 개인 설정 업데이트 엔드포인트 — PoC minimal 구현 (실제 도메인은 personalization BC)

package com.atlas.bts.identity.web

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class PreferencesController {
    // body는 PoC라 미사용 — 실제 검증은 personalization BC 정식 구현 시 (FR-PF-01).
    // 본 컨트롤러의 본질은 CSRF 토큰 검증 흐름 시연 (T5).
    @PostMapping("/api/v1/users/me/preferences")
    fun update(
        @Suppress("UnusedParameter")
        @RequestBody(required = false) body: Map<String, Any>?,
    ): ResponseEntity<Map<String, Boolean>> {
        return ResponseEntity.ok(mapOf("ok" to true))
    }
}
