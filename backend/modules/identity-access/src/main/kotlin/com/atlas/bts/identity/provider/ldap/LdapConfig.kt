// LDAP/AD 인증 공급자 연결·검색·잠금 정책 설정 VO — authn_providers.config JSONB 직렬화

package com.atlas.bts.identity.provider.ldap

/**
 * LDAP/AD 인증 공급자 연결 및 검색 설정 VO (FR-AU-02).
 *
 * authn_providers 테이블의 config 컬럼(JSONB)에 Jackson 으로 직렬화된다.
 *
 * **보안 주의사항 (DEVELOPMENT.md §1.1)**:
 * - [bindPasswordEnv] 는 환경변수 이름만 저장한다. 실제 비밀번호 값은 저장하지 않는다.
 * - 런타임에 [resolveBindPassword] 를 통해 `System.getenv(bindPasswordEnv)` 로 로딩한다.
 * - bind password 는 application startup 시 1회 로딩 후 메모리 보관을 권장한다.
 *   환경변수 변경 시 application restart 가 필요하다 (매 인증마다 syscall 회피).
 * - toString 에서 [bindDn] 을 마스킹한다 (DEVELOPMENT.md §1.2 PII 로깅 금지).
 */
data class LdapConfig(
    /** LDAP 서버 URL (예: ldap://openldap:389) */
    val serverUrl: String,
    /** 검색 시작점 DN (예: dc=bts,dc=local) */
    val baseDn: String,
    /** 서비스 계정 DN — toString 마스킹 대상 (예: cn=admin,dc=bts,dc=local) */
    val bindDn: String,
    /** bind password 환경변수 이름 (값이 아닌 이름 — 예: BTS_LDAP_BIND_PASSWORD) */
    val bindPasswordEnv: String,
    /** 사용자 검색 기준 OU (예: ou=people) */
    val userSearchBase: String,
    /** 사용자 검색 필터 — {0} 은 입력 username 으로 치환 (예: (uid={0})) */
    val userSearchFilter: String,
    /** 그룹 검색 기준 OU (예: ou=groups) */
    val groupSearchBase: String,
    /** 그룹 검색 필터 — {0} 은 user DN 으로 치환 (예: (member={0})) */
    val groupSearchFilter: String,
    /** 계정 잠금 정책 */
    val lockoutPolicy: LockoutPolicy,
    /** 사용자 이메일 LDAP 속성명 */
    val userMailAttribute: String = "mail",
    /** 사용자 표시 이름 LDAP 속성명 */
    val userDisplayNameAttribute: String = "cn",
) {
    /**
     * bind password 를 환경변수에서 로딩한다.
     * 환경변수 미설정 시 null 반환 — 호출자가 Failure(PROVIDER_UNAVAILABLE) 처리.
     * 1회 로딩 후 캐싱을 권장한다 (매 인증마다 syscall 회피).
     */
    fun resolveBindPassword(): String? = System.getenv(bindPasswordEnv)

    /** PII 마스킹 toString — bindDn 은 <masked> 로 대체 (DEVELOPMENT.md §1.2) */
    override fun toString(): String =
        "LdapConfig(serverUrl=$serverUrl, baseDn=$baseDn, bindDn=<masked>," +
            " bindPasswordEnv=$bindPasswordEnv, userSearchBase=$userSearchBase," +
            " userSearchFilter=$userSearchFilter, groupSearchBase=$groupSearchBase," +
            " lockoutPolicy=$lockoutPolicy)"
}

/**
 * 계정 잠금 정책 VO.
 * N회 연속 인증 실패 시 M분 잠금. BTS 측 자체 lockout (LDAP 서버 의존 안 함).
 * 잠금 해제는 locked_until 만료 후 자동 처리.
 */
data class LockoutPolicy(
    /** 잠금 트리거 연속 실패 횟수 */
    val maxAttempts: Int = 5,
    /** 잠금 지속 시간 (분) */
    val lockoutMinutes: Int = 15,
    /** 잠금 범위 */
    val scope: LockoutScope = LockoutScope.PER_USER_PER_PROVIDER,
)

/**
 * 계정 잠금 범위.
 * - PER_USER_PER_PROVIDER: 사용자 × Provider 조합별 독립 잠금 (기본, 권장)
 * - GLOBAL: 모든 Provider 공통 잠금 (FR-AU-06 다중 Provider 시 고려)
 */
enum class LockoutScope {
    PER_USER_PER_PROVIDER,
    GLOBAL,
}
