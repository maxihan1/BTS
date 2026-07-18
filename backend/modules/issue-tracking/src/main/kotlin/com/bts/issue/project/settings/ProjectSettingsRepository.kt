// 프로젝트 설정(name) 컬럼 UPDATE 전담 jOOQ Repository (FR-PJ PR-3 Task 5)

package com.bts.issue.project.repository

import com.bts.issue.jooq.tables.references.PROJECTS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * projects.name 컬럼의 갱신을 담당하는 Repository.
 *
 * jOOQ generated 코드 접촉은 repository 레이어로 한정한다(ArchUnit 룰 2, hexagonal 경계).
 * [com.bts.issue.project.settings.ProjectSettingsService] 가 이 Repository 를 통해 DB 에
 * 접근하며, jOOQ 를 직접 import 하지 않는다.
 *
 * ## 패키지 배치 — `com.bts.issue.project.repository` (파일 위치와 다름)
 * 이 파일은 `project/settings/` 디렉터리에 위치하지만, ArchUnit 룰 2
 * (`IssueBcArchTest.jooqGeneratedMustOnlyBeUsedInRepositoryLayer`)가 jOOQ 접촉을
 * `..repository..` 패키지 세그먼트로만 한정하므로, 기존 형제 리포지토리
 * ([com.bts.issue.project.repository.ProjectRequire2faRepository] 등)와 동일한
 * `com.bts.issue.project.repository` 패키지를 그대로 재사용한다. Kotlin 은 디렉터리와 패키지 선언의
 * 일치를 강제하지 않으므로 컴파일에는 영향이 없다.
 *
 * **deleted_at IS NULL 필터** — [com.bts.issue.project.repository.ProjectRequire2faRepository.updateRequire2fa]
 * 선례와 통일한다.
 */
@Repository
class ProjectSettingsRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 name 을 갱신한다.
     *
     * 소프트삭제된 프로젝트는 대상에서 제외한다(deleted_at IS NULL 조건).
     *
     * @param projectId 갱신 대상 프로젝트 UUID.
     * @param name 설정할 새 이름.
     * @return 영향받은 행 수(0 또는 1). 미존재·소프트삭제 시 0(경합 시나리오 판별용).
     */
    @Transactional
    fun updateName(
        projectId: UUID,
        name: String,
    ): Int {
        log.debug("ProjectSettingsRepository.updateName projectId={} name={}", projectId, name)
        return dsl
            .update(PROJECTS)
            .set(PROJECTS.NAME, name)
            .where(PROJECTS.ID.eq(projectId))
            .and(PROJECTS.DELETED_AT.isNull)
            .execute()
    }
}
