// UI 로그인 폼에 사용 가능한 인증 공급자 목록을 반환하는 엔드포인트 (FR-AU-09-22)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.spi.ProviderRegistry
import com.atlas.bts.identity.spi.ProviderType
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/v1/auth/providers — UI 로그인 폼용 공급자 목록 (FR-AU-09-22).
 *
 * - permitAll (인증 불필요): SecurityConfig 에서 해당 경로가 permitAll 처리됨.
 * - PAT 공급자 제외: PAT 는 UI 로그인 폼 대상이 아닌 API 전용 인증 방식.
 * - priority 내림차순 정렬: 높은 우선순위 공급자가 앞에 위치.
 * - available 필드: 공급자 현재 가용 여부 반영 ([AuthenticationProvider.available]).
 *
 * @see com.atlas.bts.identity.config.SecurityConfig — /api/v1/auth/providers permitAll 선언
 */
@RestController
@Transactional(readOnly = true)
class ProvidersController(
    private val providerRegistry: ProviderRegistry,
) {
    /**
     * 활성 공급자 목록 반환.
     *
     * PAT 를 제외한 모든 등록 공급자를 priority 내림차순으로 반환한다.
     */
    @GetMapping("/api/v1/auth/providers")
    fun listProviders(): ProvidersResponse {
        val providers = providerRegistry.all()
            .filter { it.type != ProviderType.PAT }
            .sortedByDescending { it.priority }
            .map { provider ->
                ProviderEntry(
                    id = provider.type.name.lowercase(),
                    type = provider.type.name,
                    displayName = provider.type.name.lowercase().replaceFirstChar { it.uppercase() },
                    priority = provider.priority,
                    available = provider.available,
                )
            }
        return ProvidersResponse(providers)
    }
}

/** providers 응답 루트 객체 */
data class ProvidersResponse(
    val providers: List<ProviderEntry>,
)

/** 공급자 항목 — UI 로그인 폼에 표시되는 단일 공급자 정보 */
data class ProviderEntry(
    /** 공급자 식별자 (소문자 type명, 예: "ldap", "local") */
    val id: String,
    /** 공급자 유형 (대문자 enum명, 예: "LDAP", "LOCAL") */
    val type: String,
    /** UI에 표시할 공급자 명칭 */
    val displayName: String,
    /** 우선순위 — 높을수록 앞에 표시 */
    val priority: Int,
    /** 현재 공급자가 정상 서비스 가능한 상태인지 여부 */
    val available: Boolean,
)
