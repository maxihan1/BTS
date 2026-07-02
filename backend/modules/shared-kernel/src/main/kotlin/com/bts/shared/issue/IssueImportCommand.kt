// CSV/JSON import 파싱 결과 1행 → IssueImportPort 커맨드 DTO
package com.bts.shared.issue

import java.time.Instant
import java.util.UUID

/**
 * 이슈 import 생성 커맨드 객체.
 *
 * search-export-import 모듈이 CSV/JSON 파일의 행 하나를 파싱한 뒤
 * [IssueImportPort.importIssue] 에 전달하는 커맨드 DTO다.
 * [com.bts.shared.search.IssueSearchQuery] 선례와 동일하게, 위치 인자 다중 파라미터 대신
 * 커맨드 객체를 채택한 이유는 후속 확장(FR-IM-02 명시적 필드 매핑)에서 포트 시그니처를
 * 변경하지 않고 필드를 추가할 수 있기 때문이다.
 *
 * ### 포트 계약상 불변 보장
 *
 * 이 클래스의 모든 필드는 val 로 선언된 불변 값이다.
 *
 * ### 이메일 기반 사용자 매핑 (PR1 고정 매핑)
 *
 * [reporterEmail]/[assigneeEmail] 은 구현체가 `UserLookupPort.resolveByEmails` 로 해석한다.
 * 미매칭 시 reporter 는 [requesterUserId](import 실행자), assignee 는 미할당(null)으로
 * 폴백하는 것이 구현체 책임이다(이 커맨드 객체 자체는 폴백을 수행하지 않는다).
 *
 * @property projectKey 이슈를 생성할 프로젝트 키. 예: `"PROJ"`.
 * @property requesterUserId import 를 실행한 사용자 UUID. CREATE_ISSUE 권한 검증 actor 이자,
 *   reporterEmail 미매칭 시 reporter 폴백 대상.
 * @property summary 이슈 제목. 빈 문자열이면 구현체가 [IssueImportResult.VALIDATION] 실패를 반환해야 한다.
 * @property typeName 이슈 유형 이름. 예: `"Bug"`. null 이면 구현체가 프로젝트 기본 유형을 사용한다.
 * @property description 이슈 본문. null 이면 미기재.
 * @property priority 우선순위 숫자 값(1..5). null 이면 구현체 기본값을 사용한다.
 * @property reporterEmail 리포터 이메일. 매칭 실패 또는 null 이면 [requesterUserId] 로 폴백한다.
 * @property assigneeEmail 담당자 이메일. 매칭 실패 또는 null 이면 미배정으로 처리한다.
 * @property labels 라벨 이름 목록. 빈 목록이면 라벨 없음.
 * @property componentNames 컴포넌트 이름 목록. 존재하지 않는 이름은 구현체가 스킵 + 경고로 처리한다(PR1).
 * @property dryRun true 이면 구현체가 검증만 수행하고 실제 이슈를 생성하지 않는다.
 * @property statusName 이슈 상태 이름. Jira export 의 `status.name` 출처. 미매칭/미존재 시
 *   구현체가 best-effort 로 경고 처리하고 기본 상태로 폴백한다.
 * @property fixVersionNames 수정 버전 이름 목록. Jira export 의 `fixVersions[].name` 출처. 미매칭/미존재
 *   버전 이름은 구현체가 best-effort 로 경고 처리하고 스킵한다.
 * @property affectsVersionNames 영향 버전 이름 목록. Jira export 의 `versions[].name`(affects) 출처.
 *   미매칭/미존재 버전 이름은 구현체가 best-effort 로 경고 처리하고 스킵한다.
 * @property comments 이슈에 동반 import 할 댓글 목록(PR3).
 * @property worklogs 이슈에 동반 import 할 작업 기록 목록(PR3).
 * @see IssueImportPort
 * @see IssueImportResult
 */
data class IssueImportCommand(
    val projectKey: String,
    val requesterUserId: UUID,
    val summary: String,
    val typeName: String? = null,
    val description: String? = null,
    val priority: Int? = null,
    val reporterEmail: String? = null,
    val assigneeEmail: String? = null,
    val labels: List<String> = emptyList(),
    val componentNames: List<String> = emptyList(),
    val dryRun: Boolean = false,
    val statusName: String? = null,
    val fixVersionNames: List<String> = emptyList(),
    val affectsVersionNames: List<String> = emptyList(),
    val comments: List<ImportComment> = emptyList(),
    val worklogs: List<ImportWorklog> = emptyList(),
)

/** import 대상 이슈에 동반 생성할 댓글 하나를 표현하는 값 객체(PR3). */
data class ImportComment(
    val body: String,
    val authorEmail: String? = null,
    val createdAt: Instant? = null,
)

/** import 대상 이슈에 동반 생성할 작업 기록(worklog) 하나를 표현하는 값 객체(PR3). */
data class ImportWorklog(
    val timeSpentSeconds: Int,
    val startedAt: Instant? = null,
    val authorEmail: String? = null,
    val comment: String? = null,
)
