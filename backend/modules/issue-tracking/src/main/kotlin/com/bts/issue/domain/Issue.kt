// Issue Aggregate Root — 이슈 단건 도메인 엔티티, factory + invariant 검증 포함

package com.bts.issue.domain

import java.time.Instant
import java.util.UUID

/**
 * 이슈 Aggregate Root.
 *
 * 이슈의 핵심 상태(키, 요약, 담당 프로젝트, 현재 워크플로우 상태)를 보유한다.
 * 직접 생성자 대신 [Issue.create] factory 를 통해 invariant 를 검증하고 인스턴스를 얻는다.
 *
 * invariant.
 * - [summary] 는 빈 문자열/공백 불가, 최대 255자.
 * - [version] 은 생성 시 1 로 고정. 낙관적 잠금(optimistic lock)에 사용.
 * - [deletedAt] 는 생성 시 null. 소프트 삭제 시 타임스탬프가 채워진다.
 *
 * @property id 불변 내부 식별자. [IssueKey] 가 바뀌어도 변하지 않는다.
 * @property key `<PROJECT_KEY>-<NUMBER>` 형식의 이슈 키. 영구 보존 (DATA.md §1.1).
 * @property projectId 이슈가 속한 프로젝트의 UUID.
 * @property summary 이슈 제목. 1~255자.
 * @property reporterId 이슈를 생성한 행위자.
 * @property currentStateKey 현재 워크플로우 상태 키. 예: `"OPEN"`.
 * @property version 낙관적 잠금 버전. 생성 시 1, 수정마다 +1.
 * @property deletedAt 소프트 삭제 타임스탬프. null 이면 삭제되지 않은 상태.
 * @property createdAt 생성 시각.
 * @property updatedAt 마지막 수정 시각.
 */
data class Issue(
    val id: IssueId,
    val key: IssueKey,
    val projectId: UUID,
    val summary: String,
    val reporterId: ActorId,
    val currentStateKey: String,
    val version: Long,
    val deletedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * 새 이슈를 생성한다.
         *
         * [summary] invariant 위반 시 [IllegalArgumentException] 을 던진다.
         *
         * @param id 이슈 내부 식별자.
         * @param key 발급된 이슈 키.
         * @param projectId 이슈가 속한 프로젝트 UUID.
         * @param summary 이슈 제목. 1~255자, 공백만으로 구성 불가.
         * @param reporterId 이슈를 생성하는 행위자.
         * @param currentStateKey 초기 워크플로우 상태 키.
         * @return 생성된 [Issue] 인스턴스.
         */
        fun create(
            id: IssueId,
            key: IssueKey,
            projectId: UUID,
            summary: String,
            reporterId: ActorId,
            currentStateKey: String,
        ): Issue {
            validateSummary(summary)
            val now = Instant.now()
            return Issue(
                id = id,
                key = key,
                projectId = projectId,
                summary = summary,
                reporterId = reporterId,
                currentStateKey = currentStateKey,
                version = 1L,
                deletedAt = null,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}

/**
 * [Issue.summary] 도메인 불변식(invariant) 검증.
 *
 * - 빈 문자열 또는 공백만으로 구성된 문자열은 거부한다.
 * - 255자를 초과하면 거부한다.
 *
 * @throws IllegalArgumentException invariant 위반 시.
 */
private fun validateSummary(summary: String) {
    require(summary.isNotBlank()) { "summary must not be blank" }
    require(summary.length <= 255) { "summary must be 255 characters or fewer, but was ${summary.length}" }
}
