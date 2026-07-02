// PAT 발급 시 지원 scope 화이트리스트와 검증(빈/미지 거부)·정규화(중복제거·순서보존)

package com.atlas.bts.identity.pat

/**
 * PAT(Personal Access Token) 발급 요청의 scope 를 검증하고 정규화하는 카탈로그.
 *
 * scope 는 "이 토큰이 무엇을 할 수 있는가" 를 나타내는 권한 라벨이다 (예: `read:issues`).
 * 발급 시점에 화이트리스트([SUPPORTED]) 밖의 값을 하드 거부해, 오타나 미래에 존재할지
 * 모를 권한을 토큰에 박제하는 사고를 원천 차단한다.
 *
 * ## 정책
 * - **화이트리스트만 허용** — [SUPPORTED] 에 정확히(exact match) 일치하지 않으면 거부한다.
 *   대소문자 차이(`READ:issues`)도 미지 scope 로 취급한다.
 * - **빈 scope 거부** — scope 없는 토큰은 발급하지 않는다 (권한 0 인 토큰은 무의미).
 * - **입력 반사 금지** — 미지 scope 원문을 예외 message 에 담지 않는다 (입력 반사 누출 방지).
 */
object PatScopeCatalog {
    /**
     * 발급 가능한 scope 화이트리스트 (ADR 확정 5종).
     *
     * `*` 는 모든 권한을 부여하는 와일드카드다.
     */
    val SUPPORTED: Set<String> =
        setOf(
            "read:issues",
            "write:issues",
            "read:projects",
            "write:projects",
            "*",
        )

    /**
     * scope 목록의 중복을 제거하되 **입력 순서를 보존**한다.
     *
     * [LinkedHashSet] 로 삽입 순서를 유지하므로 첫 등장 위치가 결과 순서를 결정한다.
     * 검증은 하지 않는다 — 정규화만 담당하며, 유효성은 [validate] 가 책임진다.
     *
     * @param scopes 원본 scope 목록 (중복 가능)
     * @return 중복이 제거되고 입력 순서가 보존된 scope 목록
     */
    fun normalize(scopes: List<String>): List<String> = LinkedHashSet(scopes).toList()

    /**
     * scope 목록이 발급 가능한 상태인지 검증한다.
     *
     * 다음 조건 중 하나라도 위반하면 예외를 던진다.
     * - 빈 목록 → [EmptyScopeException]
     * - [SUPPORTED] 에 없는 scope 포함 → [UnknownScopeException]
     *
     * @param scopes 검증할 scope 목록
     * @throws EmptyScopeException scope 목록이 비어 있을 때
     * @throws UnknownScopeException 화이트리스트 밖 scope 가 포함됐을 때
     */
    fun validate(scopes: List<String>) {
        if (scopes.isEmpty()) {
            throw EmptyScopeException()
        }
        if (scopes.any { it !in SUPPORTED }) {
            throw UnknownScopeException()
        }
    }
}

/**
 * PAT scope 목록이 비어 있을 때 던지는 도메인 예외.
 *
 * HTTP 400 Bad Request 매핑 대상 (매핑은 컨트롤러/advice 계층에서 처리한다).
 */
class EmptyScopeException : RuntimeException("scope 를 최소 1개 이상 지정해야 합니다")

/**
 * 화이트리스트([PatScopeCatalog.SUPPORTED]) 밖의 scope 가 포함됐을 때 던지는 도메인 예외.
 *
 * 입력 반사 누출을 막기 위해 미지 scope 원문은 message 에 담지 않고 일반화한다.
 * HTTP 400 Bad Request 매핑 대상 (매핑은 컨트롤러/advice 계층에서 처리한다).
 */
class UnknownScopeException : RuntimeException("지원하지 않는 scope 가 포함되어 있습니다")
