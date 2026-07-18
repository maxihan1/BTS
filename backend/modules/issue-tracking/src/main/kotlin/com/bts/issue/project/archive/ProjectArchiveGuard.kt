// 아카이브된 프로젝트에 대한 쓰기를 거부하는 중앙 잠금 술어 (FR-PJ-04 PR-4)

package com.bts.issue.project.archive

import com.bts.issue.domain.IssueKey
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 아카이브된 프로젝트(`projects.archived_at IS NOT NULL`)에 프로젝트 스코프 **쓰기**를 시도하면
 * [ProjectArchivedException](→ HTTP 409)을 던지는 중앙 잠금 술어.
 *
 * issue-tracking BC 가 `projects` 테이블을 소유하므로 cross-BC 포트 없이
 * [ProjectArchiveStateRepository] 로 직접 판정한다(D-GUARD).
 *
 * ## 두 진입점
 * - [check] — projectId(UUID) 또는 projectKey(String)를 직접 아는 쓰기 경로용
 *   (Version/Component/CustomField/IssueTemplate/Settings 등 config 계열은 projectId 보유).
 * - [checkByIssue] — issueKey 만 아는 이슈 하위리소스 쓰기 경로용
 *   (Attachment/Worklog/Watcher/Link/Move 등). issueKey → 소속 project 로 해석 후 판정한다.
 *
 * ## 판정 원칙 (★security 불변식)
 * - **`archived_at IS NOT NULL` 단독**. `deleted_at`(소프트 삭제)이나 IssueRepository 의 읽기 보안
 *   술어(`buildActiveSecureWhere`)를 참조하지 않는다 — deleted_at 오독 = 소프트 삭제가 곧 쓰기 잠금(이슈
 *   소실)으로 번역되기 때문(PJ4-6, DATA.md §3).
 * - **fail-closed 아님**. 프로젝트/이슈 미존재는 이 guard 의 책임이 아니라 상위(404)의 책임이다. guard 는
 *   "존재하는 프로젝트가 아카이브 상태인가"만 판정하고, 미존재면 통과시킨다(상위 조회가 404 를 낸다).
 * - **호출 순서**. 미인가 actor 가 409(아카이브 상태)로 프로젝트 상태를 알아내지 못하도록, 소비 서비스는
 *   항상 `assertPermission` → `check`/`checkByIssue` 순서로 호출한다(D-ORDER).
 * - **unarchive 는 대상 아님**. 아카이브 해제는 잠금을 푸는 라이프사이클 op 이므로 이 guard 로 막지 않는다(D-UNARCHIVE).
 */
@Component
class ProjectArchiveGuard(
    private val repository: ProjectArchiveStateRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * projectId(UUID)로 아카이브 여부를 판정한다. 아카이브 상태이면 [ProjectArchivedException].
     *
     * @param projectId 프로젝트 UUID.
     * @throws ProjectArchivedException 프로젝트가 아카이브 상태일 때.
     */
    fun check(projectId: UUID) {
        if (repository.isArchivedById(projectId)) {
            log.info("project_archive_locked projectId={}", projectId)
            throw ProjectArchivedException(projectId.toString())
        }
    }

    /**
     * projectKey 로 아카이브 여부를 판정한다. 아카이브 상태이면 [ProjectArchivedException].
     *
     * @param projectKey 프로젝트 키 (예: "BTS").
     * @throws ProjectArchivedException 프로젝트가 아카이브 상태일 때.
     */
    fun check(projectKey: String) {
        if (repository.isArchivedByKey(projectKey)) {
            log.info("project_archive_locked projectKey={}", projectKey)
            throw ProjectArchivedException(projectKey)
        }
    }

    /**
     * issueKey 로 소속 프로젝트의 아카이브 여부를 판정한다(이슈 하위리소스 쓰기용).
     *
     * issueKey → 소속 project 로 해석 후 그 프로젝트의 `archived_at` 단독으로 판정한다. 이슈 미존재 시
     * 통과(상위 findByKey 가 404 를 낸다).
     *
     * @param issueKey 이슈 키.
     * @throws ProjectArchivedException 이슈의 소속 프로젝트가 아카이브 상태일 때.
     */
    fun checkByIssue(issueKey: IssueKey) {
        if (repository.isArchivedByIssueKey(issueKey.value)) {
            log.info("project_archive_locked issueKey={}", issueKey.value)
            throw ProjectArchivedException(issueKey.value)
        }
    }
}
