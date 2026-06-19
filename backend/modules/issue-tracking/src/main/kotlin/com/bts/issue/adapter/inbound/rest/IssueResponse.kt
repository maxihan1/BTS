// Issue REST 응답 DTO — Issue Aggregate를 REST 레이어에서 직렬화 가능한 형태로 변환

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueImpact
import com.bts.issue.domain.IssuePriority
import com.bts.issue.markdown.MarkdownRenderer
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldRef
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 이슈 단건 REST 응답 DTO.
 *
 * [Issue] 도메인 Aggregate를 외부 API 응답 형식으로 변환한다.
 * 도메인 VO([com.bts.issue.domain.IssueId], [com.bts.issue.domain.ActorId] 등)는
 * REST 레이어에서 원시 타입(UUID, String)으로 노출한다.
 *
 * @property key 이슈 키 문자열. 예: `"ATLAS-42"`
 * @property id 이슈 내부 식별자 UUID.
 * @property projectKey 이슈가 속한 프로젝트 키. 예: `"ATLAS"`
 * @property summary 이슈 제목.
 * @property currentStateKey 현재 워크플로우 상태 키. 예: `"open"`
 * @property reporterId 이슈를 생성한 행위자의 UUID.
 * @property version 낙관적 잠금 버전.
 * @property createdAt 이슈 생성 시각.
 * @property updatedAt 이슈 마지막 수정 시각.
 * @property typeId 이슈 타입 내부 식별자 (issue_types.id). FR-IS-02.
 * @property typeKey 이슈 타입 키 문자열. 예: `"task"`. FR-IS-02.
 * @property typeName 이슈 타입 표시명. 예: `"Task"`. FR-IS-02.
 * @property description 이슈 상세 설명 원본 Markdown. null 허용.
 * @property descriptionHtml [description] 을 렌더·sanitize한 HTML. 단건 경로만 채워지며, 목록 경로는 null. C3.
 * @property priority 우선순위 숫자 1(Highest)..5(Lowest). 기본값 3.
 * @property priorityName [IssuePriority.displayName]. 예: `"Highest"`.
 * @property labels 라벨 목록.
 * @property environment 재현 환경 설명. null 허용.
 * @property impact 영향도 숫자 1(High)..3(Low). null 허용.
 * @property impactName [IssueImpact.displayName]. null 허용 (impact=null 일 때).
 * @property assigneeId 담당자 UUID. null 이면 미할당.
 * @property componentIds 이슈에 연결된 컴포넌트 UUID 목록. 단건 경로에서만 채워지며, 목록 경로는 빈 목록. FR-CM-02.
 * @property affectsVersionIds 이슈가 영향 받는 버전 UUID 목록. 단건 경로에서만 채워지며, 목록 경로는 빈 목록. FR-VR-03.
 * @property fixVersionIds 이슈를 수정할 예정인 버전 UUID 목록. 단건 경로에서만 채워지며, 목록 경로는 빈 목록. FR-VR-03.
 * @property resolution 이슈에 할당된 Resolution 요약. null 이면 미설정. FR-IS-07 B11.
 * @property resolutionId 이슈에 설정된 Resolution UUID. ApplicationService 내부 전달용. JSON 직렬화 제외.
 * @property securityLevelId 이슈에 적용된 보안 등급 UUID. null 이면 등급 없음(공개). FR-PM-06 PR-B.
 * @property customFields 커스텀 필드 값 맵. 키는 필드 정의 key, 값은 타입별 JSON 값. FR-IS-10.
 *   단건·목록 경로 모두 노출된다. issues.custom_fields JSONB 컬럼에서 직접 매핑된다.
 * @property restrictedFields actor 에게 열람 권한이 없어 마스킹된 필드 키 목록. FR-PM-07 Task-7.
 *   코어 필드는 필드명 그대로(예: `"description"`), 커스텀 필드는 정의 key(예: `"secret"`) 를 담는다.
 *   마스킹이 없으면 빈 리스트. 클라이언트는 이 목록을 통해 어떤 필드가 숨겨졌는지 인지할 수 있다.
 * @property noneditableFields actor 에게 열람은 허용되지만 편집 권한이 없는 필드 키 목록. FR-PM-07 Task-1.
 *   [restrictedFields](숨김) 에 포함된 키는 이 목록에 중복 수록되지 않는다.
 *   편집 제한이 없으면 빈 리스트. 클라이언트는 이 목록으로 입력 필드를 미리 비활성화할 수 있다.
 * @property parent 부모 이슈 요약(key, summary). **단건 조회([findByKeyWithType]) 경로에서만 채워진다.**
 *   목록 조회([listWithType]) 경로에서는 N+1/비용 회피를 위해 항상 null 로 반환된다.
 *   null 이면 JSON 키 자체를 생략한다([JsonInclude.Include.NON_NULL] 적용) — 프론트 Zod `.nullish()` 정합.
 *   FR-LK-01 Task 1.
 * @property startDate 이슈 시작일(캘린더 날짜). null 이면 미설정. FR-PL-01.
 *   JSON 직렬화 형식은 `"yyyy-MM-dd"` 문자열 — 전역 Jackson 날짜 설정이 없으므로
 *   [JsonFormat] 어노테이션으로 직접 지정한다(prod ObjectMapper 기본값 비의존).
 * @property dueDate 이슈 마감일(캘린더 날짜). null 이면 미설정. FR-PL-01.
 *   [startDate] 와 동일한 직렬화 규칙을 따른다.
 * @property targetDate 이슈 목표일(캘린더 날짜). null 이면 미설정. FR-PL-01.
 *   [startDate] 와 동일한 직렬화 규칙을 따른다.
 */
data class IssueResponse(
    val key: String,
    val id: UUID,
    val projectKey: String,
    val summary: String,
    val currentStateKey: String,
    val reporterId: UUID,
    val version: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val typeId: Long,
    val typeKey: String,
    val typeName: String,
    val description: String? = null,
    val descriptionHtml: String? = null,
    val priority: Int = DEFAULT_PRIORITY,
    val priorityName: String = IssuePriority.MEDIUM.displayName,
    val labels: List<String> = emptyList(),
    val environment: String? = null,
    val impact: Int? = null,
    val impactName: String? = null,
    val assigneeId: UUID? = null,
    val componentIds: List<UUID> = emptyList(),
    val affectsVersionIds: List<UUID> = emptyList(),
    val fixVersionIds: List<UUID> = emptyList(),
    val resolution: ResolutionSummary? = null,
    @com.fasterxml.jackson.annotation.JsonIgnore
    val resolutionId: UUID? = null,
    val securityLevelId: UUID? = null,
    val customFields: Map<String, Any?> = emptyMap(),
    val restrictedFields: List<String> = emptyList(),
    val noneditableFields: List<String> = emptyList(),
    /** 단건 조회 경로에서만 채워짐. 목록 경로는 null. null 이면 JSON 키 생략. */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val parent: ParentSummary? = null,
    /** 이슈 시작일. null 이면 미설정. "yyyy-MM-dd" 문자열 직렬화(prod ObjectMapper 기본값 비의존). */
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val startDate: LocalDate? = null,
    /** 이슈 마감일. null 이면 미설정. "yyyy-MM-dd" 문자열 직렬화. */
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val dueDate: LocalDate? = null,
    /** 이슈 목표일. null 이면 미설정. "yyyy-MM-dd" 문자열 직렬화. */
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val targetDate: LocalDate? = null,
) {
    /**
     * [visible] 집합을 기준으로 열람 불가 필드를 마스킹하고, [editable] 집합을 기준으로
     * 편집 불가 필드를 [noneditableFields] 에 기록한 새 [IssueResponse] 를 반환한다.
     *
     * FR-PM-07 Task-7 (열람 마스킹) + Task-1 (편집 불가 필드 표기).
     *
     * ### 마스킹 규칙 (spec §3.1 / §F4)
     * - CUSTOM 필드: [visible] 에 없는 customFields 맵 키를 제거 (S1).
     * - nullable CORE(description·environment·impact·assigneeId·labels):
     *   [visible] 에 없으면 null / 빈리스트 로 대체.
     * - non-null CORE(summary·priority): 마스킹 대상 아님 — 항상 노출.
     * - impactName: impact 마스킹과 연동 — impact 가 마스킹되면 impactName 도 null.
     * - 마스킹된 모든 key 를 [restrictedFields] 에 기록한다.
     *
     * ### noneditableFields 규칙 (spec §3.2 / Task-1)
     * - [visible] 에 있으나 [editable] 에 없는 필드 key 를 [noneditableFields] 에 기록한다.
     * - [restrictedFields](숨김) 에 포함된 key 는 중복 수록하지 않는다.
     * - [editable] 이 null 이면 noneditableFields 를 채우지 않는다(빈 리스트 유지).
     *
     * @param visible actor 가 열람 가능한 [FieldRef] 집합. [FieldPermissionResolver.visibleFields] 결과.
     * @param editable actor 가 편집 가능한 [FieldRef] 집합. [FieldPermissionResolver.editableFields] 결과.
     *   null 이면 편집 권한 계산을 건너뛴다(noneditableFields 빈 리스트).
     * @return 마스킹 및 noneditableFields 가 적용된 새 [IssueResponse]. 원본은 변경하지 않는다.
     */
    fun maskInvisible(
        visible: Set<FieldRef>,
        editable: Set<FieldRef>? = null,
    ): IssueResponse {
        val masked = mutableListOf<String>()

        // CUSTOM 필드 마스킹 — visible 에 없는 key 제거
        val filteredCustom =
            customFields.entries.fold(mutableMapOf<String, Any?>()) { acc, (k, v) ->
                if (FieldRef(FieldKind.CUSTOM, k) in visible) {
                    acc[k] = v
                } else {
                    masked += k
                }
                acc
            }

        // nullable CORE 필드 마스킹
        val maskedDescription = maskNullableCore("description", description, visible, masked)
        val maskedEnvironment = maskNullableCore("environment", environment, visible, masked)
        val maskedLabels: List<String>
        val maskedImpact: Int?
        val maskedImpactName: String?
        val maskedAssigneeId: UUID?

        if (FieldRef(FieldKind.CORE, "labels") !in visible) {
            maskedLabels = emptyList()
            masked += "labels"
        } else {
            maskedLabels = labels
        }

        if (FieldRef(FieldKind.CORE, "impact") !in visible) {
            maskedImpact = null
            maskedImpactName = null
            masked += "impact"
        } else {
            maskedImpact = impact
            maskedImpactName = impactName
        }

        if (FieldRef(FieldKind.CORE, "assigneeId") !in visible) {
            maskedAssigneeId = null
            masked += "assigneeId"
        } else {
            maskedAssigneeId = assigneeId
        }

        val noneditable = buildNoneditableKeys(visible, editable, masked)

        return copy(
            description = maskedDescription,
            environment = maskedEnvironment,
            labels = maskedLabels,
            impact = maskedImpact,
            impactName = maskedImpactName,
            assigneeId = maskedAssigneeId,
            customFields = filteredCustom,
            restrictedFields = masked,
            noneditableFields = noneditable,
        )
    }

    /**
     * visible 집합에서 editable 에 없는 필드 key 목록을 계산한다 (FR-PM-07 Task-1).
     *
     * [restrictedFields](masked) 에 포함된 key 는 중복 수록하지 않는다.
     * [editable] 이 null 이면 빈 리스트를 반환한다.
     *
     * @param visible actor 가 열람 가능한 [FieldRef] 집합.
     * @param editable actor 가 편집 가능한 [FieldRef] 집합. null 이면 계산을 건너뛴다.
     * @param restricted 이미 [restrictedFields] 에 기록된 key 목록. 중복 제외에 사용.
     * @return 편집 불가 필드 key 목록.
     */
    private fun buildNoneditableKeys(
        visible: Set<FieldRef>,
        editable: Set<FieldRef>?,
        restricted: List<String>,
    ): List<String> {
        if (editable == null) return emptyList()
        val restrictedSet = restricted.toSet()
        return visible
            .filter { ref -> ref !in editable }
            .map { ref -> ref.key }
            .filter { key -> key !in restrictedSet }
    }

    /**
     * nullable String CORE 필드 하나를 마스킹한다. [visible] 에 없으면 null 반환하고 [masked] 에 key 추가.
     */
    private fun maskNullableCore(
        key: String,
        value: String?,
        visible: Set<FieldRef>,
        masked: MutableList<String>,
    ): String? {
        if (FieldRef(FieldKind.CORE, key) !in visible) {
            masked += key
            return null
        }
        return value
    }

    /**
     * 이슈 타입 요약 정보. [from] 파라미터 그룹화용.
     *
     * @property id 이슈 타입 내부 식별자 (issue_types.id).
     * @property key 이슈 타입 키 문자열. 예: `"task"`.
     * @property name 이슈 타입 표시명. 예: `"Task"`.
     */
    data class IssueTypeInfo(val id: Long, val key: String, val name: String)

    /**
     * Resolution 요약 정보. 단건 조회 응답에서 현재 할당된 Resolution 을 노출한다.
     *
     * @property id Resolution DB PK(UUID).
     * @property key Resolution 슬러그 키. 예: `"fixed"`.
     * @property name Resolution 표시명. 예: `"Fixed"`.
     */
    data class ResolutionSummary(val id: UUID, val key: String, val name: String)

    /**
     * 부모 이슈 요약 정보. 단건 조회 경로에서만 채워진다 (FR-LK-01 Task 1).
     *
     * issues.parent_id → parent 이슈 self LEFT JOIN 결과를 담는다.
     * null 이면 이슈가 최상위(부모 없음)를 의미한다.
     *
     * @property key 부모 이슈 전역 식별자 문자열. 예: `"ATLAS-10"`.
     * @property summary 부모 이슈 제목.
     */
    data class ParentSummary(val key: String, val summary: String)

    companion object {
        /** DB DEFAULT 3 (Medium) 과 동기화. */
        private const val DEFAULT_PRIORITY = 3

        /**
         * [Issue] Aggregate, 프로젝트 키, 이슈 타입 요약 정보를 받아 [IssueResponse] DTO를 생성한다.
         *
         * C3 — [renderHtml] = true 이면 [Issue.description] 을 [MarkdownRenderer.renderSafe] 로 렌더하여
         * [IssueResponse.descriptionHtml] 에 채운다. false(기본값)이면 null 을 유지한다.
         * 목록(listWithType) 경로에서는 N건 렌더 비용 방지를 위해 renderHtml=false 로 호출한다.
         *
         * FR-IS-07 B11 — [resolution] 은 단건 경로에서 ApplicationService 가 채워 주입한다.
         * 목록 경로(listWithType)는 null 로 호출한다.
         *
         * FR-PM-06 PR-B — [IssueResponse.securityLevelId] 는 [Issue.securityLevelId] 에서 직접 매핑된다.
         * 단건/목록 경로 모두 동일하게 노출된다.
         *
         * FR-LK-01 Task 1 — [parent] 는 단건 조회([IssueRepository.findByKeyWithType]) 경로에서만
         * 채워진다. 목록 경로([IssueRepository.listWithType])는 null 로 호출한다(N+1/비용 회피).
         *
         * @param issue 변환할 이슈 Aggregate.
         * @param projectKey 이슈가 속한 프로젝트 키 문자열.
         * @param typeInfo 이슈 타입 요약 (id, key, name).
         * @param renderHtml true 이면 descriptionHtml 을 렌더. 단건 경로에서만 true 로 호출한다. 기본값 false.
         * @param resolution 현재 할당된 Resolution 요약. null 이면 미설정. 기본값 null.
         * @param parent 부모 이슈 요약(key, summary). 단건 경로에서 self LEFT JOIN 결과로 채운다. 기본값 null.
         */
        @Suppress("LongParameterList") // issue Aggregate + type 요약 + 경로별 선택 파라미터 조합이 불가피
        fun from(
            issue: Issue,
            projectKey: String,
            typeInfo: IssueTypeInfo,
            renderHtml: Boolean = false,
            resolution: ResolutionSummary? = null,
            parent: ParentSummary? = null,
        ): IssueResponse =
            IssueResponse(
                key = issue.key.value,
                id = issue.id.value,
                projectKey = projectKey,
                summary = issue.summary,
                currentStateKey = issue.currentStateKey,
                reporterId = issue.reporterId.value,
                version = issue.version,
                createdAt = issue.createdAt,
                updatedAt = issue.updatedAt,
                typeId = typeInfo.id,
                typeKey = typeInfo.key,
                typeName = typeInfo.name,
                description = issue.description,
                descriptionHtml = if (renderHtml) issue.description?.let { MarkdownRenderer.renderSafe(it) } else null,
                priority = issue.priority,
                priorityName = IssuePriority.fromNumber(issue.priority).displayName,
                labels = issue.labels,
                environment = issue.environment,
                impact = issue.impact,
                impactName = issue.impact?.let { IssueImpact.fromNumber(it).displayName },
                assigneeId = issue.assigneeId?.value,
                resolution = resolution,
                resolutionId = issue.resolutionId,
                securityLevelId = issue.securityLevelId,
                customFields = issue.customFields,
                parent = parent,
                startDate = issue.startDate,
                dueDate = issue.dueDate,
                targetDate = issue.targetDate,
            )
    }
}
