// 인증 공급자 접근 불가 시 던지는 도메인 예외 — HTTP 503 매핑 대상 (Task 19/21 책임)

package com.atlas.bts.identity.provider.ldap

/**
 * 인증 공급자(Provider)가 일시적으로 응답할 수 없을 때 던지는 예외 (CONCERN-4).
 *
 * **HTTP 매핑**: 상위 레이어(Task 19 / Task 21 SecurityFilterChain)가 503 Service Unavailable 로 변환한다.
 * 이 예외 자체는 HTTP 를 알지 못한다.
 *
 * **격리 정책**: ProviderRegistry 는 이 예외를 catch 하여 해당 Provider 를 건너뛰고
 * 다음 우선순위 Provider 를 시도해야 한다 (Task 35 ProviderRegistry 격리 로직 책임).
 *
 * @param message 사람이 읽을 수 있는 원인 설명 (로그 전용 — 사용자에게 노출 금지)
 * @param providerType 장애 Provider 유형 식별자 (예: "LDAP", "OIDC")
 * @param cause 원인 예외 (선택)
 */
class ProviderUnavailableException(
    message: String,
    val providerType: String = "UNKNOWN",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
