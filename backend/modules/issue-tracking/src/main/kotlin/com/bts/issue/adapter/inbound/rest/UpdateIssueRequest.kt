// PATCH /api/v1/issues/{key} 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.domain.IssueLabelConstraints
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.openapitools.jackson.nullable.JsonNullable
import java.time.LocalDate
import java.util.UUID

/**
 * 이슈 수정 REST 요청 바디.
 *
 * 변경 가능한 필드만 포함한다 (partial update, RFC 7396 JSON Merge Patch).
 * [expectedVersion] 은 낙관적 잠금(optimistic locking)을 위해 필수다.
 *
 * ### 3-상태 sentinel 규칙 (B1)
 * - description/environment: null=무변경, ""=DB NULL 클리어, 값=설정.
 *   @Pattern 빈문자열 거부 규칙은 summary 전용 — description/environment 에 복사 금지 (빈문자열=클리어 허용).
 * - labels: null=무변경, []=전체 제거, 값=교체.
 * - priority/impact: null=무변경, 값=설정.
 *
 * @property summary 새 이슈 제목. null 이면 변경하지 않는다 (Jakarta Bean Validation `@Pattern` 은
 *   null 을 통과시키므로 RFC 7396 시맨틱과 호환). 명시적 빈 문자열 또는 공백만으로 구성된 입력은
 *   400 거부 (PR #23 adversarial F-1 — `?: ""` 제거 후 빈 문자열 명시 입력 가드 추가).
 *   regex `^(?=.*\S).+$` = 비공백 문자 1자 이상 포함 강제. 최대 200자.
 * @property typeId 새 이슈 유형 ID. null 이면 변경하지 않는다 (RFC 7396 JSON Merge Patch).
 *   양수 필수. 존재하지 않거나 비활성 타입이면 404 ISSUE_TYPE_NOT_FOUND.
 * @property expectedVersion 읽어온 시점의 버전 값. DB 버전과 다르면 409 Version Conflict.
 * @property description Markdown 설명. null=무변경, ""=DB NULL 클리어, 값=설정.
 *   @Pattern 적용 없음 — 빈문자열은 클리어 sentinel 로 유효하다.
 *   최대 65535자 (@Size 제한).
 * @property priority 우선순위 1..5. null=무변경. 범위 밖이면 400.
 * @property labels 라벨 목록. null=무변경, []=전체 제거, 값=교체.
 *   목록 최대 20개는 `@field:Size` 가, **라벨 하나 최대 50자 + 공백-only 거부는
 *   [isLabelsValid](`@get:AssertTrue`)** 가 막는다.
 *   ★`List<@Size(max = 50) String>` 형태의 컨테이너 원소 제약을 **되살리지 말 것** —
 *   Kotlin 이 타입-use 애노테이션을 런타임 보존하지 않아 동작하지 않고, 이 파일이 정확히
 *   그 형태로 500 을 내보내고 있었다(2026-08-09 봉합).
 * @property environment 재현 환경 설명. null=무변경, ""=DB NULL 클리어, 값=설정.
 *   @Pattern 적용 없음 — 빈문자열은 클리어 sentinel 로 유효하다.
 *   최대 1000자 (@Size 제한).
 * @property impact 영향도 1..3. null=무변경. 범위 밖이면 400.
 * @property securityLevelId 보안 등급 UUID (FR-PM-06, Jira Cloud 방식 3-state).
 *   [JsonNullable] presence 로 구분한다 — 필드 부재(undefined)=무변경, 명시 null=해제(공개 복귀), 값=지정.
 *   기본값 [JsonNullable.undefined] 이므로 본문에 없으면 무변경이다.
 *   지정/해제 시 SET_ISSUE_SECURITY 권한을, 지정 시 적용 스킴 소속(422)을 서비스가 검증한다.
 * @property customFields 커스텀 필드 패치 맵 (FR-IS-10, E11 필드단위 병합).
 *   null=무변경, 맵 명시=키 단위 병합(나머지 기존 값 유지), 키 값 null=해당 필드 제거.
 *   required 검증은 병합 후 최종 상태 기준으로 수행한다.
 * @property startDate 시작일 (FR-PL-01, Jira Cloud 방식 3-state).
 *   [JsonNullable] presence 로 구분한다 — 필드 부재(undefined)=무변경, 명시 null=날짜 해제, 값=날짜 설정.
 *   기본값 [JsonNullable.undefined] 이므로 본문에 없으면 무변경이다. 교차 필드 검증 없음.
 * @property dueDate 마감일 (FR-PL-01). [startDate] 와 동일한 3-state 시맨틱.
 * @property targetDate 목표일 (FR-PL-01). [startDate] 와 동일한 3-state 시맨틱.
 * @property originalEstimateSeconds 최초 추정 시간 (FR-TT-01, Jira Cloud 방식 3-state, 단위: 초).
 *   [JsonNullable] presence 로 구분한다 — 필드 부재(undefined)=무변경, 명시 null=해제, 값=지정.
 *   기본값 [JsonNullable.undefined] 이므로 본문에 없으면 무변경이다. 0 이상이어야 한다 (@field:Min(0)).
 *   timeSpentSeconds 는 읽기 전용이므로 PATCH 필드로 노출하지 않는다.
 * @property remainingEstimateSeconds 잔여 추정 시간 (FR-TT-01). [originalEstimateSeconds] 와 동일한 3-state 시맨틱.
 */
data class UpdateIssueRequest(
    @field:Size(max = 200, message = "summary는 200자 이하여야 합니다.")
    @field:Pattern(
        regexp = "^(?=.*\\S).+$",
        message = "summary가 명시되었으면 공백이 아니어야 합니다.",
    )
    val summary: String?,
    @field:Positive(message = "typeId는 양수여야 합니다.")
    val typeId: Long? = null,
    @field:NotNull(message = "expectedVersion은 필수입니다.")
    val expectedVersion: Long,
    @field:Size(max = 65535, message = "description은 65535자 이하여야 합니다.")
    val description: String? = null,
    @field:Min(value = 1, message = "priority는 1 이상이어야 합니다.")
    @field:Max(value = 5, message = "priority는 5 이하여야 합니다.")
    val priority: Int? = null,
    // 개수 상한은 `@field:Size` 라 실제로 동작한다. 원소 길이 제약은 아래 [isLabelsValid] 가 본다 —
    // `List<@Size(max = 50) String>` 형태의 컨테이너 원소 제약은 **동작하지 않으므로** 쓰지 않는다.
    // 상한 값은 [IssueLabelConstraints] 가 단일 출처다 — 도메인·생성 경로와 같은 값을 본다.
    @field:Size(max = IssueLabelConstraints.MAX_COUNT, message = "라벨은 최대 20개까지 허용합니다.")
    val labels: List<String>? = null,
    @field:Size(max = 1000, message = "environment는 1000자 이하여야 합니다.")
    val environment: String? = null,
    @field:Min(value = 1, message = "impact는 1 이상이어야 합니다.")
    @field:Max(value = 3, message = "impact는 3 이하여야 합니다.")
    val impact: Int? = null,
    val securityLevelId: JsonNullable<UUID> = JsonNullable.undefined(),
    val customFields: Map<String, Any?>? = null,
    val startDate: JsonNullable<LocalDate> = JsonNullable.undefined(),
    val dueDate: JsonNullable<LocalDate> = JsonNullable.undefined(),
    val targetDate: JsonNullable<LocalDate> = JsonNullable.undefined(),
    val originalEstimateSeconds: JsonNullable<Int> = JsonNullable.undefined(),
    val remainingEstimateSeconds: JsonNullable<Int> = JsonNullable.undefined(),
) {
    /**
     * 라벨 **개별 길이 + 공백-only** 검증 (TODOS 「도메인 require 실패가 500 으로 나간다」 봉합).
     *
     * 생성 경로 [CreateIssueRequest.isLabelsValid] 와 **문자 단위로 같은 술어**다.
     * 두 경로의 판정이 갈리면 「생성은 400, 수정은 500」이라는 비대칭이 되살아난다.
     *
     * ★`List<@Size(max = 50) String>` 형태의 컨테이너 원소 제약을 쓰지 않는 이유 —
     * 이 파일이 정확히 그 형태였는데 **실제로 동작하지 않았다**. Kotlin 이 타입-use
     * 애노테이션을 런타임 보존 형태로 심지 않아 Bean Validation 이 못 본다. 그래서 51자
     * 라벨이 400 이 아니라 그대로 통과해 도메인 `Issue.validateAndNormalizeLabels` 의
     * `require` 까지 내려가고, `IssueExceptionHandler` 에 `IllegalArgumentException`
     * 핸들러가 없어 **500** 이 됐다 — 사용자 입력 오류가 서버 장애로 기록된다.
     *
     * 전역 `IllegalArgumentException → 400` 핸들러는 채택하지 않는다(2026-07-31 Maxi 확정 D-6).
     * 진짜 버그까지 400 으로 위장해 **살아 있어야 할 500 을 숨긴다.**
     *
     * ★`@get:` 타깃이 필수다. `@field:` 로 붙이면 Bean Validation 이 파생 getter 를 못 본다.
     * ★`@get:JsonIgnore` 도 필수다. 빠뜨리면 springdoc 이 `labelsValid` 를 요청 스키마에
     * 흘려 외부 소비자·문서가 없는 필드를 요구하게 된다.
     *
     * ★함수명을 바꾸지 말 것. Kotlin `val isLabelsValid` → getter `isLabelsValid()` →
     * Bean property `labelsValid` 라는 규약에 묶여 있다. 이름을 `labelsAreValid` 등으로
     * 바꾸면 **조용히 무력화**돼 지금 고치는 장식 애노테이션과 같은 양식이 재발한다.
     */
    @get:AssertTrue(message = "라벨 하나는 50자 이하이고 공백만으로 이루어질 수 없습니다.")
    @get:JsonIgnore
    val isLabelsValid: Boolean
        get() =
            labels?.all { label ->
                // 빈 문자열은 도메인이 필터링하므로 여기서 막지 않는다 (생성 경로와 대칭).
                label.isEmpty() ||
                    (label.isNotBlank() && label.length <= IssueLabelConstraints.MAX_LENGTH)
            } != false
}
