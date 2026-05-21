// 인증 공급자 유형을 나타내는 열거형 — LOCAL/LDAP/SAML/OIDC/PAT/OAUTH

package com.atlas.bts.identity.spi

/**
 * BTS가 지원하는 인증 공급자 유형.
 *
 * @property priority ProviderRegistry 정렬 기준 기본 우선순위 (높을수록 먼저 시도).
 *
 * ## Priority 우선순위 표 (SDD §19.2, FR-AU-09-28)
 * | ProviderType | priority |
 * |---|---|
 * | LDAP   | 80 |
 * | LOCAL  | 70 |
 * | PAT    | 60 |
 * | OIDC   | 50 |
 * | SAML   | 40 |
 * | OAUTH  | 30 |
 *
 * ## 동률 정책 (EC-25)
 * 두 공급자의 priority 값이 같은 경우 (커스텀 override 상황 등),
 * [ProviderRegistry]가 type.ordinal 오름차순으로 최종 순서를 결정한다.
 * enum 선언 순서가 ordinal에 영향을 주므로, 새 타입 추가 시 순서를 의도적으로 배치할 것.
 *
 * ## 새 Provider 추가 가이드
 * 1. 이 enum에 새 타입을 추가하고 priority 값을 명시적으로 지정.
 * 2. 구현체에서 `override val type = ProviderType.XXX` 설정.
 * 3. priority 기본값 외 다른 값이 필요하면 구현체에서 `override val priority = N` 설정.
 */
enum class ProviderType(val priority: Int) {
    LOCAL(70),
    LDAP(80),
    SAML(40),
    OIDC(50),
    PAT(60),
    OAUTH(30),
}
