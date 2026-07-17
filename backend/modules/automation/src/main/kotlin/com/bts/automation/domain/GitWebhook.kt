// Git 인바운드 웹훅 등록 정보를 표현하는 불변 도메인 클래스 (FR-AT-07 PR-C)

package com.bts.automation.domain

import java.time.Instant
import java.util.UUID

/**
 * `git_webhooks` 테이블(`V307__git_webhooks.sql`) 한 행에 대응하는 불변 도메인 클래스(FR-AT-07 PR-C).
 *
 * GitHub/GitLab 이 PR 머지 이벤트를 통지할 인바운드 엔드포인트의 등록 정보다. 모든 필드는 `val` 로
 * 선언해 생성 이후 변경할 수 없다. id·createdAt 은 DB DEFAULT 가 없으므로 호출자(서비스 계층)가
 * UUID·[Instant] 를 직접 확정해 전달한다(테스트 결정성 — Clock 주입 선례 동형).
 *
 * @property id 웹훅 식별자.
 * @property projectKey 웹훅이 속한 프로젝트 키(cross-BC 참조, BC 격리로 FK 아님).
 * @property provider Git 호스팅 제공자([GitProvider] — DB CHECK 와 이중 방어).
 * @property tokenHash 인바운드 URL 토큰의 SHA-256 해시(hex, 평문 토큰은 저장하지 않는다 — 발급 시 1회
 *   노출 후 미저장, DEVELOPMENT.md §1.1-1).
 * @property secretEncrypted provider 서명(HMAC) 검증용 공유 secret 의 AES-256-GCM 암호문(원문 비저장).
 * @property createdAt 생성 시각.
 * @property createdBy 생성자 BTS user id(cross-BC 참조, BC 격리로 FK 아님).
 * @property deletedAt 소프트 삭제 시각. `null` 이면 활성 상태(DATA.md §1.2 소프트삭제 우선).
 */
data class GitWebhook(
    val id: UUID,
    val projectKey: String,
    val provider: GitProvider,
    val tokenHash: String,
    val secretEncrypted: String,
    val createdAt: Instant,
    val createdBy: UUID,
    val deletedAt: Instant?,
)
