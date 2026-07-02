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
)
