// 워크플로우 초안 정의 — workflow_drafts.definition JSONB 의 형태이자 발행의 입력

package com.bts.workflow.domain

/**
 * 편집 중인 워크플로우 정의 전체. `workflow_drafts.definition` JSONB 에 그대로 직렬화된다.
 *
 * ### 왜 [com.bts.workflow.seed.WorkflowYamlDto] 를 그대로 쓰지 않는가
 * 구조는 사실상 같고, 같은 것이 「기본값으로 복원」(YAML → 초안)을 변환 없이 성립시킨다
 * (ADR `2026-08-18-workflow-db-as-source-of-truth` §D4). 그럼에도 타입을 나눈 것은
 * **형식이 바뀌는 이유가 서로 다르기** 때문이다.
 *
 * - YAML DTO — 개발자가 저장소에 커밋하는 부트스트랩 소스의 형태. 읽기 전용이다.
 * - 초안 DTO — 관리자가 화면에서 편집한 결과의 저장 형태. 편집기 전용 필드가 여기에만 붙는다
 *   (로드맵 PR 9 의 다이어그램 좌표가 그렇다).
 *
 * 한 타입으로 묶으면 편집기 사정으로 YAML 스키마가 흔들리고, 그 YAML 은 이미 배포된 환경의
 * 부트스트랩 소스다. [com.bts.workflow.seed.WorkflowYamlDto] 와의 변환은 복원 경로가 담당한다.
 *
 * ### 저장된 초안과의 호환
 * 필드를 나중에 더할 때는 **기본값을 함께 준다.** Jackson 이 누락 필드를 기본값으로 채우므로
 * 이미 저장된 초안 JSONB 가 그대로 읽힌다. 기본값 없는 필드를 더하면 그 순간 기존 초안이 전부 깨진다.
 *
 * @property key 워크플로우 식별 키. 초안은 기존 워크플로우에 종속이라 이 값은 바뀌지 않는다.
 * @property name 워크플로우 이름. 초안에서 편집 가능하다.
 * @property description 설명. null 허용.
 * @property states 상태 목록. 비어 있으면 [toWorkflow] 가 거부한다.
 * @property transitions 전환 목록.
 */
data class WorkflowDraftDefinition(
    val key: String = "",
    val name: String = "",
    val description: String? = null,
    val states: List<DraftStateDto> = emptyList(),
    val transitions: List<DraftTransitionDto> = emptyList(),
) {
    /**
     * 도메인 [Workflow] 로 변환하며 invariant 를 검증한다.
     *
     * **초안 저장과 발행이 모두 이 함수를 지난다.** 두 경로가 다른 검증을 쓰면 「초안은 저장됐는데
     * 발행에서 터지는」 상태가 생긴다 — 관리자가 고칠 방법을 모르는 막다른 길이다.
     *
     * validator·post-action 의 type·config 유효성은 여기서 보지 않는다. 그쪽은 factory 로
     * dry-run 해야 알 수 있고 [Workflow.of] 의 관심사가 아니다 —
     * [com.bts.workflow.application.TransitionRuleGuard] 가 저장·발행 양쪽에서 그것을 본다.
     *
     * ### 시작 전환 개수는 여기서 보지 않는다
     * 「정확히 1개」는 **발행** 경계의 규칙이고([initialTransitionCount] 를 `WorkflowPublishService`
     * 가 쓴다), 저장 경계의 규칙이 아니다.
     *
     * 저장까지 막으면 새로 만든 워크플로우가 초안 편집기에서 **처음부터 못 쓰인다** —
     * `WorkflowCommandService.create` 는 상태만 심고 전환을 하나도 만들지 않으므로, 편집기가
     * `GET /draft` 로 받은 본문을 그대로 `PUT` 해도 400 이 된다. 초안은 원래 불완전한 중간 상태이고,
     * 그것을 담아 두는 것이 초안이 존재하는 이유다.
     *
     * @throws IllegalArgumentException [Workflow.of] 의 invariant 를 위반할 때
     */
    fun toWorkflow(): Workflow =
        Workflow.of(
            key = key,
            name = name,
            description = description,
            states = states.map { it.toDomain() },
            transitions = transitions.map { it.toDomain() },
        )

    /** 이 정의가 담은 시작 전환의 개수. 발행 경계가 「정확히 1개」를 요구할 때 쓴다. */
    fun initialTransitionCount(): Int = transitions.count { it.kind == INITIAL_KIND }

    private companion object {
        /** `DraftTransitionDto.kind` 의 시작 전환 표기. `TransitionKind.INITIAL` 과 같은 값이다. */
        const val INITIAL_KIND = "INITIAL"
    }
}

/**
 * 초안의 상태 항목.
 *
 * ### 좌표는 편집기 전용 필드다
 * [layoutX]·[layoutY] 는 다이어그램 편집기가 노드를 어디에 그릴지만 정한다. 도메인
 * [WorkflowState] 로는 **넘기지 않는다** — 전환 계산 핫패스가 쓰지도 않는 값을 지고 다니게 되고,
 * 그러자고 읽기 경로(`WorkflowRepository` · `WorkflowStateView`)를 넓히면 그 비용이 모든 전환
 * 조회에 붙는다. 발행은 이 값을 `workflow_statuses.layout_x`·`layout_y` 로 내려쓰고, 초안 조회는
 * [com.bts.workflow.application.CurrentDefinitionReader] 가 편성 테이블에서 따로 읽어 채운다.
 *
 * @property key 상태 키. 전역 카탈로그 `statuses.key` 와 같은 값이다.
 * @property name 상태 이름.
 * @property category 카테고리 문자열 (TODO / IN_PROGRESS / DONE).
 * @property displayOrder 표시 순서. 작을수록 앞.
 * @property layoutX 다이어그램 노드의 X 좌표. null 이면 편집기가 자동 배치한다 — 0.0 으로 접지
 *   않는다. 「아직 배치한 적 없음」과 「원점에 두었음」은 다른 뜻이고, 접으면 배치한 적 없는
 *   노드가 전부 원점에 겹쳐 그려진다.
 * @property layoutY 다이어그램 노드의 Y 좌표. 위와 같다.
 */
data class DraftStateDto(
    val key: String = "",
    val name: String = "",
    val category: String = "",
    val displayOrder: Int = 0,
    val layoutX: Double? = null,
    val layoutY: Double? = null,
) {
    /**
     * 도메인 [WorkflowState] 로 변환한다.
     *
     * @throws IllegalArgumentException [category] 가 [StateCategory] 에 없는 값일 때. 조용히
     *   기본값으로 떨어뜨리지 않는다 — 모르는 카테고리를 TODO 로 접으면 보드 열이 말없이 옮겨간다.
     */
    fun toDomain(): WorkflowState =
        WorkflowState(
            key = key,
            name = name,
            category = parseCategory(),
            displayOrder = displayOrder,
        )

    private fun parseCategory(): StateCategory =
        StateCategory.entries.firstOrNull { it.name == category }
            ?: throw IllegalArgumentException(
                "상태 '$key' 의 카테고리 '$category' 를 모른다. " +
                    "쓸 수 있는 값. ${StateCategory.entries.joinToString(" · ") { it.name }}",
            )
}

/**
 * 초안의 전환 항목.
 *
 * @property from 출발 상태 키. `kind` 가 GLOBAL·INITIAL 이면 null 이어야 한다 — 출발지가 없다는
 *   것이 그 두 종류의 정의다 ([TransitionKind]).
 * @property to 도착 상태 키.
 * @property name 전환 이름. 사람이 읽는 라벨이고 매칭에 쓰지 않는다.
 * @property kind 전환 종류. NORMAL(기본) · GLOBAL · INITIAL.
 * @property validators 전환 전 검증 게이트.
 * @property postActions 전환 후 자동 처리.
 */
data class DraftTransitionDto(
    val from: String? = null,
    val to: String = "",
    val name: String = "",
    val kind: String = TransitionKind.NORMAL.name,
    val validators: List<DraftRuleDto> = emptyList(),
    val postActions: List<DraftRuleDto> = emptyList(),
) {
    /**
     * 도메인 [WorkflowTransition] 으로 변환한다.
     *
     * id 를 넘기지 않아 [WorkflowTransition] 이 임시 UUID 를 만든다. 초안은 아직 DB 행이 아니라
     * 전환 identity 가 없고, 발행 시점에 DB 가 실제 id 를 정한다.
     *
     * @throws IllegalArgumentException [kind] 가 [TransitionKind] 에 없는 값일 때
     */
    fun toDomain(): WorkflowTransition =
        WorkflowTransition(
            fromStateKey = from,
            toStateKey = to,
            name = name,
            kind = parseKind(),
        )

    private fun parseKind(): TransitionKind =
        TransitionKind.entries.firstOrNull { it.name == kind }
            ?: throw IllegalArgumentException(
                "전환 '$name' 의 종류 '$kind' 를 모른다. " +
                    "쓸 수 있는 값. ${TransitionKind.entries.joinToString(" · ") { it.name }}",
            )
}

/**
 * 초안의 전환 규칙 항목 (validator · post-action 공용).
 *
 * 두 규칙은 저장 형태가 같아(type + config) 한 타입으로 쓴다 — `workflow_validators` 와
 * `workflow_post_actions` 가 DDL 상 같은 모양이고 `TransitionRuleRepository` 도 한 구현으로
 * 둘을 처리한다. type 문자열의 유효성은 발행 시 factory dry-run 이 본다.
 *
 * @property type 규칙 타입 식별자 (예. `RequiredField` · `CALL_WEBHOOK`).
 * @property config 타입별 파라미터 맵.
 */
data class DraftRuleDto(
    val type: String = "",
    val config: Map<String, Any?> = emptyMap(),
)
