// Issue Aggregate Root — 이슈 단건 도메인 엔티티, factory + invariant 검증 포함

package com.bts.issue.domain

import com.bts.shared.issue.IssueTypeId
import java.time.Instant
import java.util.UUID

/** 라벨 한 개의 최대 글자 수. */
private const val LABEL_MAX_LENGTH = 50

/** 이슈당 허용되는 라벨 최대 개수. */
private const val LABEL_MAX_COUNT = 20

/** priority 최솟값. */
private const val PRIORITY_MIN = 1

/** priority 최댓값. */
private const val PRIORITY_MAX = 5

/** priority 기본값. */
private const val PRIORITY_DEFAULT = 3

/** impact 최솟값. */
private const val IMPACT_MIN = 1

/** impact 최댓값. */
private const val IMPACT_MAX = 3

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
 * - [typeId] 는 필수(non-null). FR-6 — 모든 이슈는 유효한 타입을 보유해야 한다.
 * - [priority] 는 1..5 범위. 기본값 3 (중간).
 * - [labels] 는 빈 문자열 자동 제거, 공백-only/50자 초과 라벨 및 21개 초과는 예외.
 *   대소문자 구분 exact match 기준으로 중복 제거한다.
 * - [impact] 는 null 또는 1..3 범위.
 *
 * @property id 불변 내부 식별자. [IssueKey] 가 바뀌어도 변하지 않는다.
 * @property key `<PROJECT_KEY>-<NUMBER>` 형식의 이슈 키. 영구 보존 (DATA.md §1.1).
 * @property projectId 이슈가 속한 프로젝트의 UUID.
 * @property typeId 이슈 유형 식별자 VO (issue_types.id FK). NOT NULL — IssueTypeId 양수 보장.
 * @property summary 이슈 제목. 1~255자.
 * @property reporterId 이슈를 생성한 행위자.
 * @property currentStateKey 현재 워크플로우 상태 키. 예: `"open"`.
 * @property version 낙관적 잠금 버전. 생성 시 1, 수정마다 +1.
 * @property deletedAt 소프트 삭제 타임스탬프. null 이면 삭제되지 않은 상태.
 * @property createdAt 생성 시각.
 * @property updatedAt 마지막 수정 시각.
 * @property description 이슈 상세 설명 (Markdown). null 허용.
 * @property priority 우선순위. 1(최고)~5(최저), 기본값 3.
 * @property labels 라벨 목록. 대소문자 보존, 중복 제거 후 저장.
 * @property environment 재현 환경 설명. null 허용.
 * @property impact 영향도. 1(치명)~3(낮음), null 허용.
 * @property assigneeId 담당자. 0~1명, null 이면 미할당.
 * @property resolutionId 종결 시 설정되는 Resolution UUID. null 이면 미설정.
 *   DONE 전이 시 서비스 계층이 설정하며, 비DONE 재전이 시 null 로 clear 된다 (FR-IS-07 B6).
 * @property componentIds 이슈가 속한 컴포넌트 UUID 목록. 중복 없음, 개수 제한 없음.
 *   [assignComponents]/[clearComponents] 를 통해 변경한다.
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
    val typeId: IssueTypeId,
    val description: String? = null,
    val priority: Int = PRIORITY_DEFAULT,
    val labels: List<String> = emptyList(),
    val environment: String? = null,
    val impact: Int? = null,
    val assigneeId: ActorId? = null,
    val resolutionId: UUID? = null,
    val componentIds: List<UUID> = emptyList(),
) {
    companion object {
        /**
         * 라벨 목록을 정규화하고 도메인 불변식을 검증한다.
         *
         * IssueApplicationService 의 PATCH 경로에서 repository 에 전달하기 전에 호출하여,
         * 라벨 도메인 검증이 생성(create) 경로뿐 아니라 수정(update) 경로에도 적용되도록 보장한다.
         *
         * 규칙.
         * - 빈 문자열("")은 자동 제거.
         * - 공백-only 라벨은 [IllegalArgumentException].
         * - [LABEL_MAX_LENGTH]자 초과 라벨은 [IllegalArgumentException].
         * - 대소문자 구분 exact match 기준 중복 제거 후 [LABEL_MAX_COUNT] 초과 시 [IllegalArgumentException].
         *
         * @param raw 정규화 전 라벨 목록.
         * @return 정규화·검증된 라벨 목록 (불변).
         * @throws IllegalArgumentException 불변식 위반 시.
         */
        fun normalizeLabels(raw: List<String>): List<String> = validateAndNormalizeLabels(raw)

        /**
         * 새 이슈를 생성한다.
         *
         * [summary] invariant 위반 시 [IllegalArgumentException] 을 던진다.
         *
         * FR-6 준수 — [typeId] 는 필수 파라미터로 default 없음.
         * 호출자(IssueApplicationService)가 task fallback 또는 지정 타입으로 반드시 유효 id 를 전달해야 한다.
         *
         * @param id 이슈 내부 식별자.
         * @param key 발급된 이슈 키.
         * @param projectId 이슈가 속한 프로젝트 UUID.
         * @param typeId 이슈 유형 식별자 VO. 반드시 활성 issue_types 행을 가리켜야 한다 (FK 무결성).
         * @param summary 이슈 제목. 1~255자, 공백만으로 구성 불가.
         * @param reporterId 이슈를 생성하는 행위자.
         * @param currentStateKey 초기 워크플로우 상태 키. 예: `"open"` (소문자, V004 마이그레이션 기준).
         * @param description 이슈 상세 설명 (Markdown). null 허용.
         * @param priority 우선순위 1..5. 기본값 3.
         * @param labels 라벨 목록. 빈 문자열 자동 제거, 공백-only/50자 초과/21개 초과 시 예외.
         * @param environment 재현 환경 설명. null 허용.
         * @param impact 영향도 1..3. null 허용.
         * @return 생성된 [Issue] 인스턴스.
         */
        @Suppress("LongParameterList")
        fun create(
            id: IssueId,
            key: IssueKey,
            projectId: UUID,
            typeId: IssueTypeId,
            summary: String,
            reporterId: ActorId,
            currentStateKey: String,
            description: String? = null,
            priority: Int = PRIORITY_DEFAULT,
            labels: List<String> = emptyList(),
            environment: String? = null,
            impact: Int? = null,
            assigneeId: ActorId? = null,
        ): Issue {
            validateSummary(summary)
            validatePriority(priority)
            val normalizedLabels = validateAndNormalizeLabels(labels)
            if (impact != null) validateImpact(impact)
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
                typeId = typeId,
                description = description,
                priority = priority,
                labels = normalizedLabels,
                environment = environment,
                impact = impact,
                assigneeId = assigneeId,
            )
        }
    }

    /**
     * 이 이슈에 담당자를 지정한다.
     *
     * 0~1명 제약 — 기존 담당자가 있으면 교체된다.
     * 버전 증가는 영속 계층(repository) 책임이므로 이 메서드에서 [version] 을 올리지 않는다.
     *
     * @param assignee 담당자 식별자.
     * @return [assigneeId] 가 [assignee] 로 설정된 새 [Issue] 인스턴스.
     */
    fun assignTo(assignee: ActorId): Issue = copy(assigneeId = assignee)

    /**
     * 이 이슈의 담당자 지정을 해제한다.
     *
     * 버전 증가는 영속 계층(repository) 책임이므로 이 메서드에서 [version] 을 올리지 않는다.
     *
     * @return [assigneeId] 가 null 로 설정된 새 [Issue] 인스턴스.
     */
    fun unassign(): Issue = copy(assigneeId = null)

    /**
     * 이 이슈에 컴포넌트 목록을 할당한다.
     *
     * 같은 컴포넌트를 두 번 이상 전달해도 중복 없이 저장되도록 [distinct] 를 적용하며,
     * 플랫폼 경계나 역직렬화 과정에서 null 이 섞여 들어오는 경우를 방어하기 위해
     * [filterNotNull] 로 null 요소를 제거한다.
     *
     * @param ids 할당할 컴포넌트 UUID 목록. 중복·null 은 자동 제거된다.
     * @return [componentIds] 가 정규화된 목록으로 교체된 새 [Issue] 인스턴스.
     */
    fun assignComponents(ids: List<UUID>): Issue = copy(componentIds = ids.filterNotNull().distinct())

    /**
     * 이 이슈의 컴포넌트 할당을 모두 해제한다.
     *
     * @return [componentIds] 가 빈 목록으로 설정된 새 [Issue] 인스턴스.
     */
    fun clearComponents(): Issue = copy(componentIds = emptyList())
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

/**
 * [Issue.priority] 도메인 불변식 검증.
 *
 * - 1..5 범위를 벗어나면 거부한다.
 *
 * @throws IllegalArgumentException invariant 위반 시.
 */
private fun validatePriority(priority: Int) {
    require(priority in PRIORITY_MIN..PRIORITY_MAX) {
        "priority must be between $PRIORITY_MIN and $PRIORITY_MAX, but was $priority"
    }
}

/**
 * [Issue.impact] 도메인 불변식 검증.
 *
 * - 1..3 범위를 벗어나면 거부한다.
 *
 * @throws IllegalArgumentException invariant 위반 시.
 */
private fun validateImpact(impact: Int) {
    require(impact in IMPACT_MIN..IMPACT_MAX) {
        "impact must be between $IMPACT_MIN and $IMPACT_MAX, but was $impact"
    }
}

/**
 * [Issue.labels] 도메인 불변식 검증 + 정규화.
 *
 * - 빈 문자열("")은 자동 제거한다.
 * - 공백만으로 구성된 라벨은 예외를 던진다.
 * - [LABEL_MAX_LENGTH]자를 초과하는 라벨은 예외를 던진다.
 * - 대소문자 구분 exact match 기준으로 중복을 제거한 후 반환한다.
 * - 중복 제거 후 [LABEL_MAX_COUNT]개를 초과하면 예외를 던진다.
 *
 * @throws IllegalArgumentException invariant 위반 시.
 * @return 정규화된 라벨 리스트 (불변).
 */
private fun validateAndNormalizeLabels(labels: List<String>): List<String> {
    val filtered = labels.filter { it.isNotEmpty() }
    for (label in filtered) {
        require(label.isNotBlank()) {
            "label must not be whitespace-only, but was \"$label\""
        }
        require(label.length <= LABEL_MAX_LENGTH) {
            "label must be $LABEL_MAX_LENGTH characters or fewer, but was ${label.length}: \"$label\""
        }
    }
    val deduped = filtered.distinct()
    require(deduped.size <= LABEL_MAX_COUNT) {
        "labels must not exceed $LABEL_MAX_COUNT per issue, but was ${deduped.size}"
    }
    return deduped
}
