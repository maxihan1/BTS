// 워크플로우 스킴 Aggregate Root — 4 표준 스킴 도메인 모델 (Jira workflowscheme align)

package com.bts.workflow.scheme.domain

import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 워크플로우 스킴(WorkflowScheme) Aggregate Root.
 *
 * 워크플로우 스킴은 이슈 타입별로 어떤 워크플로우를 사용할지 정의하는 구성 단위다.
 * Jira의 `workflowscheme` 테이블 개념과 동일하며, 프로젝트에 1:1로 배정된다.
 *
 * ## spec §5.1.1 표준 스킴 (G7 결정)
 *
 * `is_default = true`인 스킴은 시스템 표준 스킴으로, Jira의 `workflowscheme.is_default` 패턴을
 * 따른다. 표준 스킴은 삭제 불가(SchemeStandardNotDeletableException)하며
 * key·name·description·is_default 4 필드가 잠겨 있다(SchemeStandardFieldLockedException).
 * 신규 프로젝트가 스킴을 명시적으로 지정하지 않으면 기본 표준 스킴이 자동 배정된다.
 *
 * ## 생성
 *
 * 직접 생성자 호출을 막고 [create] companion factory를 통해서만 인스턴스를 생성한다.
 * factory가 [name] 빈 문자열 불변식을 검증하므로 인스턴스가 존재하면 항상 일관된 상태를 보장한다.
 *
 * ## 동일성
 *
 * Aggregate Root의 identity는 [key] 기반이다.
 * [id]가 null(저장 전)인 상태에서도 [key]로 동등성을 비교할 수 있다.
 *
 * @property id DB 저장 전 null, 저장 후 WorkflowSchemeId 할당. `workflow_schemes.id BIGINT`.
 * @property key URL-safe 소문자 슬러그 식별자. 시스템 전역 고유. @see WorkflowSchemeKey.
 * @property name 사람이 읽을 수 있는 스킴 이름. 빈 문자열 불허.
 * @property description 스킴 설명. 관리자용. null 허용 (DB nullable 컬럼).
 * @property isDefault true = 시스템 표준 스킴. Jira workflowscheme.is_default 패턴.
 * @property projectId 소유 프로젝트(`projects.id`). null = 전역 공유 템플릿(SYSTEM_ADMIN 소관),
 * 값이 있으면 그 프로젝트 전용(그 프로젝트의 PROJECT_ADMIN 소관). FR-WF-08.
 * @property createdAt 스킴 생성 시각 (UTC).
 * @property updatedAt 스킴 최종 변경 시각 (UTC).
 * @property deletedAt Soft delete 시각. null = 활성 상태.
 *
 * @suppress LongParameterList — 스킴이 실제로 갖는 필드 수다. 묶어 봐야 인위적인 그룹만 생기고
 * factory 가 검증하는 불변식(name 이 비었나)이 한 겹 뒤로 밀린다.
 */
@Suppress("LongParameterList")
class WorkflowScheme private constructor(
    val id: WorkflowSchemeId?,
    val key: WorkflowSchemeKey,
    val name: String,
    val description: String?,
    val isDefault: Boolean,
    val projectId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
) {
    companion object {
        /**
         * WorkflowScheme 인스턴스 생성 factory.
         *
         * 불변식 검증.
         * - [name]은 빈 문자열 또는 공백만 있는 문자열 불허.
         *
         * Clock 주입을 통해 createdAt/updatedAt을 결정 가능하므로 테스트에서 시간 고정 가능.
         *
         * @param key 스킴 식별 키. @see WorkflowSchemeKey 유효성은 VO 생성자가 보장.
         * @param name 스킴 이름. 빈 문자열/공백만 허용하지 않음.
         * @param description 스킴 설명. null 허용.
         * @param isDefault 표준 스킴 여부. 기본값 false.
         * @param projectId 소유 프로젝트. null = 전역 템플릿. **기본값을 두지 않는다** — 빠뜨리면
         * 조용히 전역이 되고 그 스킴은 프로젝트 관리자가 영영 못 고친다(FR-WF-08).
         * @param clock 생성·변경 시각 기준 시계. 테스트에서 고정 시각 주입에 사용.
         * @throws IllegalArgumentException [name]이 빈 문자열이거나 공백만 있을 때.
         */
        fun create(
            key: WorkflowSchemeKey,
            name: String,
            description: String?,
            isDefault: Boolean = false,
            projectId: UUID?,
            clock: Clock = Clock.systemUTC(),
        ): WorkflowScheme {
            require(name.isNotBlank()) {
                "WorkflowScheme name must not be blank"
            }

            val now = Instant.now(clock)
            return WorkflowScheme(
                id = null,
                key = key,
                name = name,
                description = description,
                isDefault = isDefault,
                projectId = projectId,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        }

        /**
         * DB 조회 결과 복원용 factory (Repository 전용).
         *
         * [create] 와 달리 이미 DB 에 저장된 인스턴스를 복원할 때 사용한다.
         * [id] 는 반드시 non-null (DB 저장 후 BIGSERIAL id 할당 완료 상태).
         * 불변식 검증 없이 DB 상태를 그대로 복원한다.
         *
         * @param id DB `workflow_schemes.id` 값.
         * @param key 스킴 키.
         * @param name 스킴 이름.
         * @param description 스킴 설명. null 허용.
         * @param isDefault 표준 스킴 여부.
         * @param projectId 소유 프로젝트. null = 전역 템플릿.
         * @param createdAt 생성 시각.
         * @param updatedAt 최종 변경 시각.
         * @param deletedAt soft-delete 시각. null = 활성.
         */
        fun reconstruct(
            id: WorkflowSchemeId,
            key: WorkflowSchemeKey,
            name: String,
            description: String?,
            isDefault: Boolean,
            projectId: UUID?,
            createdAt: Instant,
            updatedAt: Instant,
            deletedAt: Instant?,
        ): WorkflowScheme =
            WorkflowScheme(
                id = id,
                key = key,
                name = name,
                description = description,
                isDefault = isDefault,
                projectId = projectId,
                createdAt = createdAt,
                updatedAt = updatedAt,
                deletedAt = deletedAt,
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkflowScheme) return false
        return key == other.key
    }

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "WorkflowScheme(key=${key.value}, name=$name, isDefault=$isDefault)"
}
