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
 * ### userId 우선 규칙 (PR2 명시적 사용자 매핑)
 *
 * 구현체(어댑터)는 [reporterUserId]/[assigneeUserId] 가 있으면 이메일 해석보다 우선한다.
 * null 이면 기존 [reporterEmail]/[assigneeEmail] 폴백 규칙을 그대로 따른다.
 *
 * @property projectKey 이슈를 생성할 프로젝트 키. 예: `"PROJ"`.
 * @property requesterUserId import 를 실행한 사용자 UUID. CREATE_ISSUE 권한 검증 actor 이자,
 *   reporterEmail 미매칭 시 reporter 폴백 대상.
 * @property summary 이슈 제목. 빈 문자열이면 구현체가 [IssueImportResult.VALIDATION] 실패를 반환해야 한다.
 * @property typeName 이슈 유형 이름. 예: `"Bug"`. null 이면 구현체가 프로젝트 기본 유형을 사용한다.
 * @property description 이슈 본문. null 이면 미기재.
 * @property priority 우선순위 숫자 값(1..5). null 이면 구현체 기본값을 사용한다.
 * @property reporterEmail 리포터 이메일. 매칭 실패 또는 null 이면 [requesterUserId] 로 폴백한다.
 *   [reporterUserId] 가 있으면 이 필드보다 우선한다.
 * @property assigneeEmail 담당자 이메일. 매칭 실패 또는 null 이면 미배정으로 처리한다.
 *   [assigneeUserId] 가 있으면 이 필드보다 우선한다.
 * @property labels 라벨 이름 목록. 빈 목록이면 라벨 없음.
 * @property componentNames 컴포넌트 이름 목록. 존재하지 않는 이름은 구현체가 스킵 + 경고로 처리한다(PR1).
 * @property dryRun true 이면 구현체가 검증만 수행하고 실제 이슈를 생성하지 않는다.
 * @property statusName 이슈 상태 이름. Jira export 의 `status.name` 출처. 미매칭/미존재 시
 *   구현체가 best-effort 로 경고 처리하고 기본 상태로 폴백한다.
 * @property fixVersionNames 수정 버전 이름 목록. Jira export 의 `fixVersions[].name` 출처. 미매칭/미존재
 *   버전 이름은 구현체가 best-effort 로 경고 처리하고 스킵한다.
 * @property affectsVersionNames 영향 버전 이름 목록. Jira export 의 `versions[].name`(affects) 출처.
 *   미매칭/미존재 버전 이름은 구현체가 best-effort 로 경고 처리하고 스킵한다.
 * @property comments 이슈에 동반 import 할 댓글 목록. 빈 목록이면 댓글 없음(PR3).
 * @property worklogs 이슈에 동반 import 할 작업 기록(worklog) 목록. 빈 목록이면 worklog 없음(PR3).
 * @property sourceKey 원본(Jira 등) 이슈 키. 예: `"JIRA-123"`. [ImportAttachmentSource.open] 이 첨부
 *   파일을 찾을 때 사용하는 힌트이며, 신규 이슈의 [projectKey]/키 발급과는 무관하다. null 이면
 *   첨부 매칭을 시도하지 않는다(구현체 책임).
 * @property attachments 이슈에 동반 import 할 첨부 파일 메타 목록(PR4). 빈 목록이면 첨부 없음.
 *   실제 바이너리는 이 커맨드가 담지 않고 [ImportAttachmentSource] 를 통해 구현체가 조회한다.
 * @property changelog 이슈에 동반 import 할 변경 이력(changelog) 그룹 목록(PR4). 빈 목록이면 이력 없음.
 *   원본(Jira 등)의 changelog history 를 그대로 재생하기 위한 목록이며, 신규 필드 변경 감지(detector)를
 *   거치지 않고 구현체가 그대로 기록한다.
 * @property reporterUserId 프로세서가 명시적으로 해석한 리포터 사용자 UUID(PR2). null 이면
 *   구현체가 [reporterEmail] 폴백 규칙을 따른다.
 * @property assigneeUserId 프로세서가 명시적으로 해석한 담당자 사용자 UUID(PR2). null 이면
 *   구현체가 [assigneeEmail] 폴백 규칙을 따른다.
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
    val sourceKey: String? = null,
    val attachments: List<ImportAttachment> = emptyList(),
    val changelog: List<ImportChangeGroup> = emptyList(),
    val reporterUserId: UUID? = null,
    val assigneeUserId: UUID? = null,
)

/**
 * import 대상 이슈에 동반 생성할 댓글 하나를 표현하는 값 객체(PR3).
 *
 * [IssueImportCommand.comments] 목록의 원소로만 사용되며, 프레임워크 의존 없는 순수 데이터다.
 * 구현체(issue-tracking `IssueImportAdapter`)가 실제 댓글 도메인 레코드로 변환하는 책임을 진다
 * (이 VO 자체는 검증·변환 로직을 갖지 않는다).
 *
 * @property body 댓글 본문. 빈 문자열 처리는 구현체 책임.
 * @property authorEmail 작성자 이메일. 매칭 실패 또는 null 이면 구현체가
 *   [IssueImportCommand.requesterUserId](import 실행자)로 폴백한다
 *   ([IssueImportCommand.reporterEmail] 폴백 규칙과 동일).
 * @property createdAt 원본(Jira 등) 작성 시각. null 이면 구현체가 import 실행 시각을 사용한다.
 * @property authorUserId 프로세서가 명시적으로 해석한 작성자 사용자 UUID(PR2). 있으면
 *   구현체가 [authorEmail] 해석보다 우선한다. null 이면 [authorEmail] 폴백 규칙을 따른다.
 */
data class ImportComment(
    val body: String,
    val authorEmail: String? = null,
    val createdAt: Instant? = null,
    val authorUserId: UUID? = null,
)

/**
 * import 대상 이슈에 동반 생성할 작업 기록(worklog) 하나를 표현하는 값 객체(PR3).
 *
 * [IssueImportCommand.worklogs] 목록의 원소로만 사용되며, 프레임워크 의존 없는 순수 데이터다.
 * 구현체(issue-tracking `IssueImportAdapter`)가 실제 worklog 도메인 레코드로 변환하는 책임을 진다
 * (이 VO 자체는 검증·변환 로직을 갖지 않는다).
 *
 * @property timeSpentSeconds 소요 시간(초). 원본(Jira 등)의 시간 표기를 초 단위로 환산한 값.
 * @property startedAt 작업 시작 시각. null 이면 구현체가 import 실행 시각을 사용한다.
 * @property authorEmail 작성자 이메일. 매칭 실패 또는 null 이면 구현체가
 *   [IssueImportCommand.requesterUserId](import 실행자)로 폴백한다
 *   ([IssueImportCommand.reporterEmail] 폴백 규칙과 동일).
 * @property comment worklog 에 첨부된 코멘트. null 이면 미기재.
 * @property authorUserId 프로세서가 명시적으로 해석한 작성자 사용자 UUID(PR2). 있으면
 *   구현체가 [authorEmail] 해석보다 우선한다. null 이면 [authorEmail] 폴백 규칙을 따른다.
 */
data class ImportWorklog(
    val timeSpentSeconds: Int,
    val startedAt: Instant? = null,
    val authorEmail: String? = null,
    val comment: String? = null,
    val authorUserId: UUID? = null,
)

/**
 * import 대상 이슈에 동반 생성할 첨부 파일 하나의 메타데이터를 표현하는 값 객체(PR4).
 *
 * [IssueImportCommand.attachments] 목록의 원소로만 사용되며, 프레임워크 의존 없는 순수 데이터다.
 * 실제 바이너리 내용은 이 VO 가 담지 않는다 — 구현체가 [ImportAttachmentSource.open] 을 호출해
 * [filename] 과 [IssueImportCommand.sourceKey] 로 스트림을 조회한다(zip 내 파일명 매칭 등은
 * [ImportAttachmentSource] 구현체 책임).
 *
 * @property filename 원본 파일명. [ImportAttachmentSource.open] 호출 시 매칭 키로 사용된다.
 * @property authorEmail 업로더 이메일. 매칭 실패 또는 null 이면 구현체가
 *   [IssueImportCommand.requesterUserId](import 실행자)로 폴백한다
 *   ([IssueImportCommand.reporterEmail] 폴백 규칙과 동일).
 * @property createdAt 원본(Jira 등) 업로드 시각. null 이면 구현체가 import 실행 시각을 사용한다.
 * @property mimeType 원본 MIME 타입. null 이면 구현체가 파일 내용/확장자 기반으로 재판정한다.
 * @property sizeBytes 원본 파일 크기(바이트). 실제 조회한 스트림 크기와 다를 수 있으며 참고용이다.
 * @property authorUserId 프로세서가 명시적으로 해석한 업로더 사용자 UUID(PR2). 있으면
 *   구현체가 [authorEmail] 해석보다 우선한다. null 이면 [authorEmail] 폴백 규칙을 따른다.
 */
data class ImportAttachment(
    val filename: String,
    val authorEmail: String? = null,
    val createdAt: Instant? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val authorUserId: UUID? = null,
)

/**
 * import 대상 이슈에 동반 재생할 변경 이력(changelog) 그룹 하나를 표현하는 값 객체(PR4).
 *
 * 원본(Jira 등)의 changelog history 한 건(한 시점에 한 작성자가 여러 필드를 동시 변경한 단위)에
 * 대응한다. [IssueImportCommand.changelog] 목록의 원소로만 사용되며, 프레임워크 의존 없는
 * 순수 데이터다. 신규 필드 변경 감지(detector)를 거치지 않고 구현체가 [items] 를 그대로 기록한다.
 *
 * @property authorEmail 변경을 수행한 작성자 이메일. 매칭 실패 또는 null 이면 구현체가
 *   [IssueImportCommand.requesterUserId](import 실행자)로 폴백한다
 *   ([IssueImportCommand.reporterEmail] 폴백 규칙과 동일).
 * @property occurredAt 원본(Jira 등) 변경 발생 시각. null 이면 구현체가 import 실행 시각을 사용한다.
 * @property items 이 그룹에 속한 필드별 변경 항목 목록. 빈 목록이면 변경 항목 없음.
 * @property authorUserId 프로세서가 명시적으로 해석한 변경 수행자 사용자 UUID(PR2). 있으면
 *   구현체가 [authorEmail] 해석보다 우선한다. null 이면 [authorEmail] 폴백 규칙을 따른다.
 */
data class ImportChangeGroup(
    val authorEmail: String? = null,
    val occurredAt: Instant? = null,
    val items: List<ImportChangeItem> = emptyList(),
    val authorUserId: UUID? = null,
)

/**
 * [ImportChangeGroup] 에 속한 필드 하나의 변경 전/후 값을 표현하는 값 객체(PR4).
 *
 * [field] 는 BTS 내부 필드명이 아니라 원본(Jira 등) export 의 raw 필드명을 그대로 담는다
 * (예: `"status"`, `"assignee"`). BTS 필드로의 매핑은 이 VO 자체가 하지 않으며 구현체 책임이다.
 *
 * @property field 원본(Jira 등)의 raw 필드명. BTS 내부 필드명으로 매핑되지 않은 원본 값이다.
 * @property fromValue 변경 전 값(원본 표기 그대로). null 이면 이전 값 없음(신규 설정).
 * @property toValue 변경 후 값(원본 표기 그대로). null 이면 값 제거.
 */
data class ImportChangeItem(
    val field: String,
    val fromValue: String? = null,
    val toValue: String? = null,
)
