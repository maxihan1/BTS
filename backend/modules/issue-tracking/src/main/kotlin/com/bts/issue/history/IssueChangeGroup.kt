// 이슈 변경 이력 그룹 — V018 issue_change_group 테이블의 도메인 표현

package com.bts.issue.history

import java.time.Instant
import java.util.UUID

/**
 * 하나의 이슈 변경 작업에서 발생한 모든 [IssueChangeItem] 을 묶는 그룹.
 *
 * V018 마이그레이션의 `issue_change_group` 테이블 컬럼과 대응한다.
 * append-only — 생성 후 수정·삭제 없음 (DATA.md §3 감사 로그 불변 원칙).
 *
 * @property issueId 변경된 이슈의 UUID (issue_change_group.issue_id). FK 없음 — 이력 보존 우선.
 * @property issueKey 변경 시점의 이슈 키 스냅샷. 키가 바뀌어도 이력은 당시 키를 보존한다.
 * @property actorId 변경을 수행한 행위자의 UUID. null 이면 시스템 자동 처리 또는 미식별.
 * @property items 이 그룹에 속한 필드 변경 내역 목록.
 * @property createdAt 그룹 생성 시각. null 이면 저장 전 도메인 객체.
 */
data class IssueChangeGroup(
    val issueId: UUID,
    val issueKey: String,
    val actorId: UUID?,
    val items: List<IssueChangeItem>,
    val createdAt: Instant? = null,
)
