// 이슈 타입 Aggregate Root — 5 표준 (Epic/Story/Task/Subtask/Bug) + 커스텀 후속 FR-IS-02
package com.bts.issue.type.domain

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import java.time.Instant

/**
 * 이슈 타입 Aggregate Root.
 *
 * 하나의 이슈(Issue)에 정확히 하나의 IssueType 이 할당된다.
 * 표준 5종 (Epic / Story / Task / Subtask / Bug) 은 [companion object] 상수로 제공되며
 * isStandard = true 로 표시된다.
 *
 * **PR scope 안내.**
 * 본 PR (FR-WF-02) 에서는 5종 표준 entity 정의 및 V003 DB seed 만 포함한다.
 * CRUD API 및 커스텀 IssueType 생성은 후속 FR-IS-02 PR scope 다.
 *
 * **BC 교차 도입.**
 * ADR `issue-type-cross-bc-introduction` 에 따라 project-workflow BC 에서 IssueTypeScheme 을
 * 구성하기 위해 issue-tracking 모듈에 IssueType 을 사전 도입한다.
 *
 * @property id DB PK. 신규 생성 전(DB 저장 전)에는 null 이다.
 * @property key URL-safe 소문자 슬러그 키. 예: `"task"`, `"bug"`.
 * @property name 표시 이름. 빈 문자열 불가.
 * @property description 선택적 설명. null 허용.
 * @property iconName 아이콘 식별자. null 허용.
 * @property isStandard 표준 타입 여부. 표준 5종은 true, 커스텀은 false.
 * @property hierarchyLevel 이슈 유형 계층 깊이. epic=1(최상위), task/story/bug=0(기본), subtask=-1(하위).
 *   허용 범위: {-1, 0, 1}. 범위 외 값으로 생성 시 [IllegalArgumentException] 발생.
 * @property createdAt 생성 시각.
 * @property updatedAt 최종 수정 시각.
 * @property deletedAt 소프트 삭제 시각. null 이면 활성 상태.
 */
data class IssueType(
    val id: IssueTypeId?,
    val key: IssueTypeKey,
    val name: String,
    val description: String?,
    val iconName: String?,
    val isStandard: Boolean,
    val hierarchyLevel: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
) {
    companion object {
        /** 허용된 [IssueType.hierarchyLevel] 값 집합. */
        private val ALLOWED_HIERARCHY_LEVELS = setOf(-1, 0, 1)

        /**
         * IssueType 인스턴스를 생성하는 factory 메서드.
         *
         * - [name] 이 빈 문자열이거나 공백만으로 구성된 경우 [IllegalArgumentException] 을 던진다.
         * - [hierarchyLevel] 이 허용 범위(-1, 0, 1) 밖이면 [IllegalArgumentException] 을 던진다.
         * - [id] 는 DB 저장 전이므로 null 로 초기화된다.
         * - [createdAt], [updatedAt] 은 현재 시각으로 초기화된다.
         * - [deletedAt] 은 null 로 초기화된다 (활성 상태).
         *
         * @param key 이슈 타입 키. URL-safe 소문자 슬러그.
         * @param name 표시 이름. 공백 트림 후 빈 문자열이면 예외.
         * @param description 선택적 설명. 기본값 null.
         * @param iconName 아이콘 식별자. 기본값 null.
         * @param isStandard 표준 타입 여부. 기본값 false.
         * @param hierarchyLevel 계층 깊이. {-1, 0, 1} 허용. 기본값 0.
         */
        fun create(
            key: IssueTypeKey,
            name: String,
            description: String? = null,
            iconName: String? = null,
            isStandard: Boolean = false,
            hierarchyLevel: Int = 0,
        ): IssueType {
            require(name.isNotBlank()) { "IssueType name must not be blank" }
            require(hierarchyLevel in ALLOWED_HIERARCHY_LEVELS) {
                "IssueType hierarchyLevel must be one of $ALLOWED_HIERARCHY_LEVELS, got: $hierarchyLevel"
            }
            val now = Instant.now()
            return IssueType(
                id = null,
                key = key,
                name = name,
                description = description,
                iconName = iconName,
                isStandard = isStandard,
                hierarchyLevel = hierarchyLevel,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        }

        /** 표준 이슈 타입 — Epic. 에픽(큰 사용자 스토리 묶음). hierarchyLevel=1(최상위). */
        val EPIC: IssueType =
            create(
                key = IssueTypeKey("epic"),
                name = "Epic",
                description = "대규모 작업 단위. 여러 Story 로 분해된다.",
                iconName = "epic",
                isStandard = true,
                hierarchyLevel = 1,
            )

        /** 표준 이슈 타입 — Story. 사용자 관점에서 정의된 기능 단위. hierarchyLevel=0. */
        val STORY: IssueType =
            create(
                key = IssueTypeKey("story"),
                name = "Story",
                description = "사용자 스토리. 하나의 기능을 사용자 관점으로 서술한다.",
                iconName = "story",
                isStandard = true,
                hierarchyLevel = 0,
            )

        /** 표준 이슈 타입 — Task. 구체적인 작업 항목. hierarchyLevel=0. */
        val TASK: IssueType =
            create(
                key = IssueTypeKey("task"),
                name = "Task",
                description = "구체적인 작업 항목. Story 를 달성하기 위한 단위 작업.",
                iconName = "task",
                isStandard = true,
                hierarchyLevel = 0,
            )

        /** 표준 이슈 타입 — Subtask. Task 를 세분화한 하위 작업. hierarchyLevel=-1(하위). */
        val SUBTASK: IssueType =
            create(
                key = IssueTypeKey("subtask"),
                name = "Subtask",
                description = "Task 를 세분화한 하위 작업 단위.",
                iconName = "subtask",
                isStandard = true,
                hierarchyLevel = -1,
            )

        /** 표준 이슈 타입 — Bug. 결함 및 오류 항목. hierarchyLevel=0. */
        val BUG: IssueType =
            create(
                key = IssueTypeKey("bug"),
                name = "Bug",
                description = "시스템 결함 또는 예상치 못한 동작.",
                iconName = "bug",
                isStandard = true,
                hierarchyLevel = 0,
            )
    }
}
