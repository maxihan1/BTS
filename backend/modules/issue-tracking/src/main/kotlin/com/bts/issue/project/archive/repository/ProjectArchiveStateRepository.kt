// 프로젝트 아카이브 상태를 archived_at 단독으로 조회하는 jOOQ Repository (읽기 술어와 분리, FR-PJ-04 PR-4)

package com.bts.issue.project.archive.repository

import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.PROJECTS
import org.jooq.Condition
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * `projects.archived_at IS NOT NULL` **단독**으로 프로젝트의 아카이브 여부를 판정하는 Repository.
 *
 * jOOQ generated 코드 접촉은 repository 레이어로 한정한다(ArchUnit 룰 2, hexagonal 경계). 그래서
 * 이 클래스는 `com.bts.issue.project.archive.repository` 패키지에 두고(=`..repository..` 화이트리스트),
 * [com.bts.issue.project.archive.ProjectArchiveGuard] 는 이 Repository 를 통해서만 DB 에 접근한다.
 *
 * ## ★ 판정 술어는 archived_at 단독 — deleted_at/읽기 술어 절대 미참조
 * 아카이브(`archived_at`)와 소프트 삭제(`deleted_at`, DATA.md §3)는 **직교하는 별도 라이프사이클 축**이다
 * (V037, PJ4-6). 이 Repository 는 오직 `archived_at` 만 본다.
 * - `deleted_at` 을 아카이브로 오독하면 **소프트 삭제가 곧 프로젝트 스코프 쓰기 잠금(→ 이슈 소실)** 으로
 *   번역된다. DATA.md §3 은 소프트 삭제 술어를 수동·개별 적용하도록 규정하므로 여기서 자동 결합해선 안 된다.
 * - IssueRepository 의 읽기 보안 술어(`buildActiveSecureWhere`, archived 무참조)도 참조하지 않는다 — 그것은
 *   읽기 경로 술어이며 아카이브 잠금과 무관하다(PJ4-6, 읽기 불변).
 */
@Repository
class ProjectArchiveStateRepository(
    private val dsl: DSLContext,
) {
    /**
     * 아카이브 판정 술어.
     *
     * RED(판별자 입증): `deleted_at` 을 함께 참조하는 잘못된 술어를 주입한다 — 소프트 삭제된
     * 프로젝트를 아카이브로 오판하게 만들어, `ProjectArchiveGuardTest` 의 "삭제된 프로젝트를 아카이브로
     * 오판하지 않음" 판별자가 실제로 fail 하는지 확인한다. GREEN 에서 `archived_at` 단독으로 정정한다.
     */
    private fun archivedCondition(): Condition = PROJECTS.ARCHIVED_AT.isNotNull.or(PROJECTS.DELETED_AT.isNotNull)

    /**
     * projectId(UUID)로 아카이브 여부를 판정한다.
     *
     * 프로젝트 미존재 시 `false`(행 없음 → 아카이브 아님). 미존재는 이 Repository 의 책임이 아니라
     * 상위(404)의 책임이므로 여기서 예외를 던지지 않는다(fail-closed 아님).
     *
     * @param projectId 프로젝트 UUID.
     * @return 아카이브 상태이면 true.
     */
    @Transactional(readOnly = true)
    fun isArchivedById(projectId: UUID): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(PROJECTS)
                .where(PROJECTS.ID.eq(projectId))
                .and(archivedCondition()),
        )

    /**
     * projectKey 로 아카이브 여부를 판정한다.
     *
     * @param projectKey 프로젝트 키 (예: "BTS").
     * @return 아카이브 상태이면 true. 미존재 시 false.
     */
    @Transactional(readOnly = true)
    fun isArchivedByKey(projectKey: String): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(PROJECTS)
                .where(PROJECTS.KEY.eq(projectKey))
                .and(archivedCondition()),
        )

    /**
     * issueKey 로 소속 프로젝트의 아카이브 여부를 판정한다(issueKey → project_id 해석).
     *
     * `issues.key = ?` 로 이슈를 찾아 `issues.project_id = projects.id` 조인해 소속 프로젝트의
     * `archived_at` 을 본다. 이슈 미존재 시 false(행 없음). 아카이브 판정은 소속 프로젝트의
     * `archived_at` 단독이며, 이슈의 `deleted_at` 은 참조하지 않는다.
     *
     * @param issueKey 이슈 키 원문 (예: "BTS-1").
     * @return 소속 프로젝트가 아카이브 상태이면 true. 이슈 미존재 시 false.
     */
    @Transactional(readOnly = true)
    fun isArchivedByIssueKey(issueKey: String): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(ISSUES)
                .join(PROJECTS).on(ISSUES.PROJECT_ID.eq(PROJECTS.ID))
                .where(ISSUES.KEY.eq(issueKey))
                .and(archivedCondition()),
        )
}
