// IssueApplicationService 입력 DTO — application 계층 request 데이터 클래스

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.shared.issue.IssueTypeId
import java.time.LocalDate
import java.util.UUID

/**
 * 이슈 생성 요청 DTO.
 *
 * @param projectKey 이슈를 생성할 프로젝트 키. 예: "BTS".
 * @param typeId 이슈 유형 식별자 VO. null 이면 서비스가 task 타입으로 fallback 한다 (FR-6).
 * @param summary 이슈 제목. 1~255자.
 * @param reporterId 이슈 생성자 ActorId.
 * @param description 이슈 설명 (Markdown). null 또는 공백이면 서비스가 템플릿으로 대체한다 (FR-TM-01 옵션 C).
 *   non-blank 이면 요청 값을 그대로 사용하고 템플릿을 조회하지 않는다.
 * @param componentIds 이슈 생성 시 연결할 컴포넌트 UUID 목록. 빈 목목이면 컴포넌트 미연결로 생성한다 (FR-CM-03).
 * @param securityLevelId 이슈에 지정할 보안 등급 UUID (FR-PM-06). null 이면 등급 없음(공개).
 *   non-null 이면 서비스가 SET_SECURITY 권한 + 적용 스킴 소속을 검증한다.
 * @param customFields 커스텀 필드 값 맵 (FR-IS-10). null 이면 빈 맵으로 처리한다.
 *   값 검증(타입/required/미정의키)은 서비스에서 수행한다.
 * @param assignee 담당자 지정 의도 3-state (FR-UX-09 B1, ADR D-2). 기본 [AssigneeIntent.Auto].
 * @param priority 우선순위 1..5 (FR-UX-09 B1). null 이면 서비스가 [com.bts.issue.domain.IssuePriority.MEDIUM] 기본값을 적용한다.
 * @param labels 라벨 목록 (FR-UX-09 B1). null 이면 빈 목록. 정규화·상한 검증은 도메인 [com.bts.issue.domain.Issue] 가 수행한다.
 * @param notifyAssignment 담당자 확정 시 `IssueAssigned` 발행 여부 (FR-UX-09 B1, ADR D-5).
 *   **기본 false 는 의도된 fail-safe 다.** 이 함수의 생산자는 REST 컨트롤러 하나가 아니라
 *   Import 어댑터(`IssueImportAdapter`)도 있고, Import 는 생성 직후 `changeAssignee` 로 담당자를
 *   다시 지정해 그쪽에서 이미 `IssueAssigned` 를 발행한다. 기본값을 true 로 두면 반입 1건당
 *   알림이 2회 나가고 첫 번째는 곧 덮어쓰일 임시 담당자에 대한 거짓 알림이 된다.
 *   **기본값을 뒤집지 말 것** — 앞으로 생길 새 생산자도 알림이 꺼진 채로 태어나야 한다.
 */
data class CreateIssueRequest(
    val projectKey: String,
    val summary: String,
    val reporterId: ActorId,
    val typeId: IssueTypeId? = null,
    val description: String? = null,
    val componentIds: List<UUID> = emptyList(),
    val securityLevelId: UUID? = null,
    val customFields: Map<String, Any?>? = null,
    val assignee: AssigneeIntent = AssigneeIntent.Auto,
    val priority: Int? = null,
    val labels: List<String>? = null,
    val notifyAssignment: Boolean = false,
)

/**
 * 이슈 생성 시 담당자 지정 의도 3-state (FR-UX-09 B1, ADR D-2).
 *
 * 생성 시맨틱에서 "필드 부재(자동 배정 유지)" 와 "명시 null(미할당 확정)" 을 구분하기 위한 sealed 표현이다.
 * 컨트롤러(transport)가 `JsonNullable<UUID>` 의 presence 를 이 타입으로 변환하여 서비스에 전달한다.
 * 웹 직렬화 라이브러리(JsonNullable)에 application 계층이 결합되지 않도록 별도 타입으로 분리한다
 * ([DatePatch] · [SecurityLevelPatch] 와 동일 선례).
 *
 * [Auto] 와 [None] 의 차이가 이 타입의 존재 이유다 — 2-state 로는
 * "자동 배정을 끄고 미할당으로 두기" 를 표현할 수 없다.
 */
sealed interface AssigneeIntent {
    /** 요청에 `assigneeId` 키가 없음 — `resolveDefaultAssignee` 자동 배정을 그대로 유지한다(기존 동작). */
    data object Auto : AssigneeIntent

    /** `assigneeId: null` 명시 — 자동 배정을 **비활성**하고 미할당으로 확정한다. */
    data object None : AssigneeIntent

    /** `assigneeId: <uuid>` — 자동 배정을 비활성하고 [userId] 를 담당자로 확정한다. */
    data class User(val userId: UUID) : AssigneeIntent
}

/**
 * 이슈 일정 날짜 수정 의도 3-state (FR-PL-01).
 *
 * PATCH 시맨틱에서 "필드 부재(무변경)" 와 "명시 null(해제)" 를 구분하기 위한 sealed 표현이다.
 * 컨트롤러(transport)가 `JsonNullable<LocalDate>` 의 presence 를 이 타입으로 변환하여 서비스에 전달한다.
 * 웹 직렬화 라이브러리(JsonNullable)에 application 계층이 결합되지 않도록 별도 타입으로 분리한다.
 * startDate / dueDate / targetDate 세 필드가 동일 타입을 공용으로 사용한다.
 *
 * when 식에서 else 분기 없이 컴파일러가 완전성(exhaustiveness)을 보장한다.
 */
sealed interface DatePatch {
    /** 필드 부재 — 날짜를 변경하지 않는다. */
    data object Unchanged : DatePatch

    /** 명시 null — 날짜를 해제하여 미지정 상태로 되돌린다. */
    data object Clear : DatePatch

    /**
     * 값 지정 — 날짜를 [value] 로 설정한다.
     *
     * @param value 지정할 캘린더 날짜 (DATE, 타임존 무관).
     */
    data class Set(val value: LocalDate) : DatePatch
}

/**
 * 이슈 추정 시간 수정 의도 3-state (FR-TT-01).
 *
 * PATCH 시맨틱에서 "필드 부재(무변경)" 와 "명시 null(해제)" 를 구분하기 위한 sealed 표현이다.
 * originalEstimate / remainingEstimate 두 필드가 동일 타입을 공용으로 사용한다.
 * [DatePatch] 와 동형 구조 — when 식에서 else 분기 없이 컴파일러가 완전성을 보장한다.
 */
sealed interface EstimatePatch {
    /** 필드 부재 — 추정 값을 변경하지 않는다. */
    data object Unchanged : EstimatePatch

    /** 명시 null — 추정 값을 해제하여 미추정 상태로 되돌린다. */
    data object Clear : EstimatePatch

    /**
     * 값 지정 — 추정 값을 [value](초)로 설정한다.
     *
     * @param value 지정할 추정 시간(초). 호출자가 양수임을 보장해야 한다.
     */
    data class Set(val value: Int) : EstimatePatch
}

/**
 * 이슈 보안 등급 수정 의도 3-state (FR-PM-06, Jira Cloud 방식).
 *
 * PATCH 시맨틱에서 "필드 부재(무변경)" 와 "명시 null(해제)" 를 구분하기 위한 sealed 표현이다.
 * 컨트롤러(transport)가 `JsonNullable<UUID>` 의 presence 를 이 타입으로 변환하여 서비스에 전달한다.
 * 웹 직렬화 라이브러리(JsonNullable)에 application 계층이 결합되지 않도록 별도 타입으로 분리한다.
 *
 * when 식에서 else 분기 없이 컴파일러가 완전성(exhaustiveness)을 보장한다.
 */
sealed interface SecurityLevelPatch {
    /** 필드 부재 — 보안 등급을 변경하지 않는다. */
    data object Unchanged : SecurityLevelPatch

    /** 명시 null — 보안 등급을 해제하여 공개 상태로 되돌린다. */
    data object Clear : SecurityLevelPatch

    /**
     * 값 지정 — 보안 등급을 [levelId] 로 지정한다.
     *
     * @param levelId 지정할 보안 등급 UUID.
     */
    data class Assign(val levelId: UUID) : SecurityLevelPatch
}

/**
 * 이슈 수정 요청 DTO (RFC 7396 JSON Merge Patch 시맨틱).
 *
 * 각 필드는 null 이면 "변경하지 않음"을 의미한다.
 * CREATE 의 typeId=null → task fallback 과 달리, PATCH 의 typeId=null 은 타입 유지를 의미한다.
 *
 * ### 3-상태 sentinel 규칙 (B1)
 * - description/environment: null=무변경, ""=DB NULL 클리어, 값=설정.
 * - labels: null=무변경, []=전체 제거, 값=교체.
 * - priority/impact: null=무변경, 값=설정. (범위 위반 시 서비스에서 IllegalArgumentException)
 *
 * @param summary 새 이슈 제목. null 이면 변경하지 않는다.
 * @param typeId 새 이슈 유형 식별자 VO. null 이면 변경하지 않는다. non-null 이면 활성 타입 존재 검증.
 * @param expectedVersion 낙관적 잠금 버전. 읽은 version 값과 일치해야 업데이트가 성공한다.
 * @param description Markdown 설명. null=무변경, ""=클리어, 값=설정.
 * @param priority 우선순위 1..5. null=무변경.
 * @param labels 라벨 목록. null=무변경, []=전체 제거, 값=교체.
 * @param environment 재현 환경 설명. null=무변경, ""=클리어, 값=설정.
 * @param impact 영향도 1..3. null=무변경.
 * @param securityLevel 보안 등급 수정 의도 (FR-PM-06). [SecurityLevelPatch] 3-state —
 *   Unchanged=무변경(기본), Clear=해제, Assign=지정. 무변경 외에는 SET_SECURITY 권한을 검증한다.
 * @param customFields 커스텀 필드 패치 맵 (FR-IS-10, E11). null=무변경, 맵 명시=키 단위 병합,
 *   키 값 null=해당 필드 제거. required 검증은 병합 후 최종 상태 기준.
 * @param startDate 시작일 수정 의도 (FR-PL-01). [DatePatch] 3-state —
 *   Unchanged=무변경(기본), Clear=날짜 해제, Set=날짜 지정. 교차 필드 검증 없음.
 * @param dueDate 마감일 수정 의도 (FR-PL-01). [DatePatch] 3-state —
 *   Unchanged=무변경(기본), Clear=날짜 해제, Set=날짜 지정.
 * @param targetDate 목표일 수정 의도 (FR-PL-01). [DatePatch] 3-state —
 *   Unchanged=무변경(기본), Clear=날짜 해제, Set=날짜 지정.
 * @param originalEstimate 최초 추정 시간 수정 의도 (FR-TT-01). [EstimatePatch] 3-state —
 *   Unchanged=무변경(기본), Clear=해제, Set=지정(초). 수동 추정 변경 = 정식 이슈 수정(version 증가).
 * @param remainingEstimate 잔여 추정 시간 수정 의도 (FR-TT-01). [EstimatePatch] 3-state —
 *   Unchanged=무변경(기본), Clear=해제, Set=지정(초). 수동 추정 변경 = 정식 이슈 수정(version 증가).
 */
data class UpdateIssueRequest(
    val summary: String?,
    val typeId: IssueTypeId? = null,
    val expectedVersion: Long,
    val description: String? = null,
    val priority: Int? = null,
    val labels: List<String>? = null,
    val environment: String? = null,
    val impact: Int? = null,
    val securityLevel: SecurityLevelPatch = SecurityLevelPatch.Unchanged,
    val customFields: Map<String, Any?>? = null,
    val startDate: DatePatch = DatePatch.Unchanged,
    val dueDate: DatePatch = DatePatch.Unchanged,
    val targetDate: DatePatch = DatePatch.Unchanged,
    val originalEstimate: EstimatePatch = EstimatePatch.Unchanged,
    val remainingEstimate: EstimatePatch = EstimatePatch.Unchanged,
)

/**
 * 이슈 전이 요청 DTO.
 *
 * workflowKey 는 [com.bts.issue.application.IssueApplicationService.transitionIssue] 가
 * [com.bts.shared.workflow.WorkflowKeyResolver] 를 통해 자동 결정한다.
 * 컨트롤러(transport 계층) 는 toStateKey 만 전달한다.
 *
 * transition identity = (from, to) — ADR 2026-05-28-workflow-transition-identity-policy 참조.
 *
 * @param toStateKey 목표 상태 키. 예: "in_progress".
 * @param expectedVersion 낙관적 잠금 버전.
 * @param resolutionId DONE 상태로 전이할 때 지정하는 해결책 UUID.
 *   비DONE 전이에서는 무시되고 서비스 계층에서 clear 처리된다.
 *   BulkItemApplier 등 기존 생성 지점 호환을 위해 기본값 null로 선언한다.
 */
data class TransitionIssueRequest(
    val toStateKey: String,
    val expectedVersion: Long,
    val resolutionId: UUID? = null,
)

/**
 * 이슈 담당자 변경 요청 DTO (FR-IS-03).
 *
 * assigneeId=null 은 담당자 해제(unassign)를 의미한다.
 * non-null 이면 [com.bts.shared.user.UserLookupPort] 로 사용자 실재를 검증한다.
 *
 * @param assigneeId 새 담당자 UUID. null 이면 해제.
 * @param expectedVersion 낙관적 잠금 버전. 읽은 version 값과 일치해야 업데이트가 성공한다.
 */
data class AppChangeAssigneeRequest(
    val assigneeId: UUID?,
    val expectedVersion: Long,
)

/**
 * 이슈 컴포넌트 변경 요청 DTO (FR-CM-02).
 *
 * 이슈에 연결된 컴포넌트 목록을 교체한다(전체 replace).
 * 중복 ID 는 도메인 [com.bts.issue.domain.Issue.assignComponents] 에서 distinct 처리된다.
 *
 * @param componentIds 새로 연결할 컴포넌트 UUID 목록. 빈 목록이면 전체 해제.
 * @param expectedVersion 낙관적 잠금 버전. 읽은 version 값과 일치해야 업데이트가 성공한다.
 */
data class AppChangeComponentsRequest(
    val componentIds: List<UUID>,
    val expectedVersion: Long,
)

/**
 * 이슈 버전 연결 변경 요청 DTO (FR-VR-03).
 *
 * "영향받는 버전"(affects) 과 "수정 예정 버전"(fix) 교체에 공용으로 사용한다.
 * 이슈에 연결된 버전 목록을 전체 교체(replace-all)한다.
 * 중복 ID 는 도메인 [com.bts.issue.domain.Issue.assignAffectsVersions] /
 * [com.bts.issue.domain.Issue.assignFixVersions] 에서 distinct 처리된다.
 *
 * @param versionIds 새로 연결할 버전 UUID 목록. 빈 목록이면 전체 해제.
 * @param expectedVersion 낙관적 잠금 버전. 읽은 version 값과 일치해야 업데이트가 성공한다.
 */
data class AppChangeVersionsRequest(
    val versionIds: List<UUID>,
    val expectedVersion: Long,
)

/**
 * 이슈 클론 요청 DTO (FR-IS-06).
 *
 * 클론은 원본 이슈의 필드(summary/description/typeId/priority/labels/environment/impact)를 복사한
 * 새 이슈를 같은 프로젝트에 생성한다. key/reporter/상태/version/시각은 새로 시작한다.
 * 첨부/Watcher/댓글은 미구현이므로 복사 대상이 아니다 (ADR 2026-06-02-issue-clone-semantics).
 *
 * @param includeAssignee true(기본) 면 원본 담당자를 클론본에 복사. false 면 미할당으로 클론.
 * @param summaryOverride 클론본 제목 덮어쓰기. null 또는 공백만이면 원본 summary 를 그대로 사용한다.
 * @param notifyAssignment 담당자가 복사됐을 때 `IssueAssigned` 발행 여부 (ADR D-5 형태).
 *   **기본 false 는 의도된 fail-safe 다.** [CreateIssueRequest.notifyAssignment] 와 같은 원칙 —
 *   앞으로 생길 새 생산자(대량 복제 등)가 알림을 **꺼진 채로 태어나게** 한다.
 *   현재 클론 진입점은 단건 REST 하나뿐이라(`IssueController.clone`) 「현존 대량 경로 방어」가
 *   아니라 **defense-in-depth** 로 정당화된다. **기본값을 뒤집지 말 것.**
 */
data class CloneIssueRequest(
    val includeAssignee: Boolean = true,
    val summaryOverride: String? = null,
    val notifyAssignment: Boolean = false,
)
