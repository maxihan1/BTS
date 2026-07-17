// Git 웹훅 provider 화이트리스트 — DB CHECK 제약과 이중 방어 (FR-AT-07 PR-C)

package com.bts.automation.domain

/**
 * `git_webhooks.provider` 화이트리스트(FR-AT-07 PR-C).
 *
 * `V307__git_webhooks.sql` 의 `ck_git_webhooks_provider CHECK (provider IN ('GITHUB','GITLAB'))`
 * 와 이중 방어한다 — 앱(이 enum)에서 한 번, DB(CHECK)에서 한 번. 데이터 무결성은 시스템의 마지막
 * 방어선이므로 앱 검증만으로 끝내지 않는다(V307 마이그레이션 파일 KDoc 동형).
 *
 * ## ★ enum 이름은 서명 검증기 상수 문자열과 정확히 일치해야 한다
 * `com.bts.automation.security.GitWebhookSignatureVerifier` 는 이 enum 을 import 할 수 없는
 * 독립 작업(의존성 없음)으로 먼저 구현되어 `provider: String` 파라미터와 `PROVIDER_GITHUB`/
 * `PROVIDER_GITLAB` 문자열 상수("GITHUB"/"GITLAB")로 비교한다. 이 enum 의 값 이름([GITHUB]/
 * [GITLAB])이 그 상수 문자열과 다르면 `enum.name` 을 넘기는 호출자의 모든 서명 검증이
 * fail-closed(거부)로 무너진다 — 반드시 대문자 "GITHUB"/"GITLAB" 그대로 유지할 것.
 */
enum class GitProvider {
    GITHUB,
    GITLAB,
}
