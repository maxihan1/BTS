// CSV/JSON import 파싱 결과 1행 — 코어 필드만 담는 불변 값 객체
package com.bts.search.imports.parse

/**
 * CSV/JSON import 파일의 데이터 행 1건을 파싱한 결과.
 *
 * [ImportRowParser] 가 CSV 데이터 행 또는 JSON `issues[].fields` 객체 1건을 이 타입으로 변환한다.
 * 코어 필드만 담으며, 이후 [com.bts.shared.issue.IssueImportCommand] 로 변환되어
 * `IssueImportPort` 에 전달된다 (Task 9, `ImportJobProcessor`).
 *
 * 모든 필드는 원본 셀/JSON 텍스트 값에 [ImportRowParser] 의 정화(NUL/제어문자 제거)만 적용한 상태다.
 * 이메일 매칭·유형 매핑(typeId)·컴포넌트 존재 확인 등 도메인 검증은 다음 단계(Task 8/9) 책임이다.
 *
 * @property rowNumber 원본 파일에서의 1-기준 데이터 행 번호(헤더/이슈 배열 순서 기준, 헤더 제외).
 *   에러 로그에서 실패 행을 식별하는 데 사용된다.
 * @property summary 이슈 제목. 빈 값/누락 시 null(구현체가 검증 실패로 처리해야 한다).
 * @property description 이슈 본문. 누락 시 null.
 * @property typeName 이슈 유형 이름(예: `"Bug"`). null 이면 구현체가 프로젝트 기본 유형으로 폴백한다.
 * @property priorityName 정규화된 우선순위 이름(`"Highest"`/`"High"`/`"Medium"`/`"Low"`/`"Lowest"` 중 하나).
 *   원본 값이 이름·숫자(1..5) 어느 형식이었든 이 필드에는 정규화된 이름만 담긴다.
 *   범위 밖 숫자 또는 미인식 이름은 null(무시).
 * @property reporterEmail 리포터 이메일 원본 문자열. 매칭은 후속 단계(`UserLookupPort`) 책임.
 * @property assigneeEmail 담당자 이메일 원본 문자열.
 * @property labels 라벨 이름 목록. CSV 는 콤마/세미콜론으로 분리한 값, JSON 은 배열 원소 그대로.
 * @property componentNames 컴포넌트 이름 목록. CSV 는 콤마/세미콜론으로 분리한 값, JSON 은 `components[].name`.
 * @property statusName 이슈 상태 이름(예: `"In Progress"`). CSV 는 `status` 컬럼, JSON 은 `fields.status.name`.
 *   null 이면 구현체가 프로젝트 초기 상태로 폴백한다.
 * @property fixVersionNames 수정 버전 이름 목록. CSV 는 `fix version` 컬럼(콤마/세미콜론 분리),
 *   JSON 은 `fields.fixVersions[].name`.
 * @property affectsVersionNames 영향 버전 이름 목록. CSV 는 `affects version` 컬럼(콤마/세미콜론 분리),
 *   JSON 은 `fields.versions[].name`.
 * @property comments 이슈에 동반 import 할 댓글 목록(PR3). CSV 는 동명 `Comment` 컬럼 전부(각 셀
 *   `date;author;body` 세미콜론 분해), JSON 은 `fields.comment.comments[]`. 원본 순서를 보존한다.
 * @property worklogs 이슈에 동반 import 할 작업 기록(worklog) 목록(PR3). JSON `fields.worklog.worklogs[]`
 *   전용 — CSV 는 Jira 표준 worklog export 형식이 없어 미지원(항상 emptyList).
 * @property sourceKey 원본(Jira) 이슈 키(예: `"JIRA-1"`, PR4). JSON `issues[].key` 전용 — CSV 는
 *   미지원(항상 null). 새로 발급되는 BTS 이슈 키와는 무관하며, import 이력 추적용 원본 참조값이다.
 * @property attachments 이슈에 동반 import 할 첨부파일 메타데이터 목록(PR4). JSON
 *   `fields.attachment[]` 전용 — CSV 는 미지원(항상 emptyList). 실제 파일 바이너리는 이 단계에서
 *   내려받지 않고 메타데이터만 담는다(다운로드/저장은 후속 단계 책임).
 * @property changelog 이슈에 동반 import 할 변경 이력(changelog) 목록(PR4). JSON
 *   `changelog.histories[]` 전용 — CSV 는 미지원(항상 emptyList).
 */
data class ParsedImportRow(
    val rowNumber: Int,
    val summary: String?,
    val description: String?,
    val typeName: String?,
    val priorityName: String?,
    val reporterEmail: String?,
    val assigneeEmail: String?,
    val labels: List<String>,
    val componentNames: List<String>,
    val statusName: String? = null,
    val fixVersionNames: List<String> = emptyList(),
    val affectsVersionNames: List<String> = emptyList(),
    val comments: List<ParsedImportComment> = emptyList(),
    val worklogs: List<ParsedImportWorklog> = emptyList(),
    val sourceKey: String? = null,
    val attachments: List<ParsedImportAttachment> = emptyList(),
    val changelog: List<ParsedImportChangeGroup> = emptyList(),
)

/**
 * CSV/JSON import 파일에서 파싱한 댓글 1건의 raw 값(PR3).
 *
 * [ImportRowParser] 가 직접 생성하는 파서-로컬 값 객체다. [createdAt] 은 원본 문자열 그대로 담고
 * (아직 [java.time.Instant] 로 변환하지 않는다), `Instant` 변환은 [com.bts.shared.issue.ImportComment]
 * 로 매핑하는 시점([com.bts.search.imports.job.application.ImportJobProcessor.toCommand])에서 수행한다.
 *
 * @property body 댓글 본문. 빈 문자열/공백 처리는 다음 단계(Task 7 어댑터) 책임.
 * @property authorEmail 작성자 이메일 원본 문자열. 매칭·소문자화는 다음 단계 책임.
 * @property createdAt 원본 작성 시각 문자열(ISO-8601 기대). 파싱 가능 여부 판단은 다음 단계 책임.
 */
data class ParsedImportComment(
    val body: String,
    val authorEmail: String?,
    val createdAt: String?,
)

/**
 * CSV/JSON import 파일에서 파싱한 작업 기록(worklog) 1건의 raw 값(PR3).
 *
 * JSON `fields.worklog.worklogs[]` 전용 — CSV 는 미지원이라 이 타입은 JSON 파서에서만 생성된다.
 *
 * @property timeSpentSeconds 소요 시간(초). JSON 값이 숫자가 아니거나 없으면 0(다음 단계에서
 *   `timeSpentSeconds <= 0` 검증으로 best-effort 스킵 처리).
 * @property startedAt 원본 작업 시작 시각 문자열(ISO-8601 기대). [ParsedImportComment.createdAt] 과
 *   동일하게 원본 문자열을 그대로 담는다.
 * @property authorEmail 작성자 이메일 원본 문자열.
 * @property comment worklog 에 첨부된 코멘트. 없으면 null.
 */
data class ParsedImportWorklog(
    val timeSpentSeconds: Int,
    val startedAt: String?,
    val authorEmail: String?,
    val comment: String? = null,
)

/**
 * CSV/JSON import 파일에서 파싱한 첨부파일 메타데이터 1건의 raw 값(PR4).
 *
 * JSON `fields.attachment[]` 전용 — CSV 는 미지원이라 이 타입은 JSON 파서에서만 생성된다.
 * [created] 는 [ParsedImportComment.createdAt] 과 동일하게 원본 문자열을 그대로 담는다.
 *
 * @property filename 원본 파일명. 값이 없으면 빈 문자열(구현체가 검증 실패로 처리해야 한다).
 * @property authorEmail 업로드한 사용자 이메일 원본 문자열.
 * @property created 원본 업로드 시각 문자열(ISO-8601 기대).
 * @property mimeType 원본 MIME 타입 문자열. 없으면 null.
 * @property sizeBytes 원본 파일 크기(byte). JSON 값이 숫자가 아니거나 없으면 null.
 */
data class ParsedImportAttachment(
    val filename: String,
    val authorEmail: String?,
    val created: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
)

/**
 * CSV/JSON import 파일에서 파싱한 변경 이력(changelog) 그룹 1건의 raw 값(PR4).
 *
 * JSON `changelog.histories[]` 전용 — CSV 는 미지원이라 이 타입은 JSON 파서에서만 생성된다.
 * Jira 는 한 시점에 여러 필드가 함께 바뀌면 [items] 배열 하나에 변경 항목을 모아 담는다.
 *
 * @property authorEmail 변경을 수행한 사용자 이메일 원본 문자열.
 * @property created 원본 변경 시각 문자열(ISO-8601 기대).
 * @property items 이 그룹에서 함께 변경된 필드 항목 목록. 원본 순서를 보존한다.
 */
data class ParsedImportChangeGroup(
    val authorEmail: String?,
    val created: String?,
    val items: List<ParsedImportChangeItem>,
)

/**
 * 변경 이력 그룹 하나에 속한 필드 변경 항목 1건의 raw 값(PR4).
 *
 * @property field 변경된 필드 이름(Jira 원본 이름, 예: `"status"`/`"assignee"`). 값이 없으면 빈 문자열.
 * @property fromValue 변경 전 값 문자열(`fromString`). 없으면 null.
 * @property toValue 변경 후 값 문자열(`toString`). 없으면 null.
 */
data class ParsedImportChangeItem(
    val field: String,
    val fromValue: String?,
    val toValue: String?,
)
