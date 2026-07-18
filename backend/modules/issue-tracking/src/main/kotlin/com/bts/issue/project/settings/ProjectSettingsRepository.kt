// 프로젝트 설정(name) 컬럼 UPDATE 전담 jOOQ Repository (FR-PJ PR-3 Task 5)

package com.bts.issue.project.settings

import com.bts.issue.jooq.tables.references.PROJECTS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * projects.name 컬럼의 갱신을 담당하는 Repository.
 *
 * [com.bts.issue.project.settings.ProjectSettingsService] 가 이 Repository 를 통해 DB 에
 * 접근하며, jOOQ 를 직접 import 하지 않는다.
 *
 * ## ArchUnit 룰 2(`IssueBcArchTest.jooqGeneratedMustOnlyBeUsedInRepositoryLayer`) 기존 위반 — 확대 아님
 * 이 룰은 jOOQ 접촉을 `..repository..` 패키지 세그먼트로만 한정한다. 이 파일이 위치한
 * `com.bts.issue.project.settings` 는 그 패턴에 해당하지 않아 이 룰을 위반하지만, detekt
 * `InvalidPackageDeclaration`(패키지 선언 = 파일 위치 강제)과 상충해 두 검증을 동시에 만족할 수
 * 없다 — 패키지를 `com.bts.issue.project.repository` 로 바꾸면 detekt 가, 파일을 그 디렉터리로
 * 옮기면 이 태스크의 선언 파일 경로를 벗어난다. 같은 PR 의 Task 1
 * ([com.bts.issue.project.query.ProjectQueryRepository], `com.bts.issue.project.query` 패키지)이
 * 이미 동일한 사유로 이 룰을 위반한 상태이므로(선행 회귀, 이 클래스가 최초 원인이 아님) 이 클래스는
 * 그 위반을 새 유형으로 확대하지 않고 동일 사유에 합류한다. 근본 해결(룰 2 패턴에 `..query..`/
 * `..settings..` 추가 또는 리포지토리 재배치)은 이 태스크 범위 밖 — PR 리뷰/후속 태스크로 보고한다.
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
