# 10. 검색 / Export / Import

## 10.1 AQL (Atlas Query Language)

JQL과 99% 호환되는 쿼리 언어. ANTLR 4로 구현.

> **[Deviation — PR #189]** "ANTLR 4로 구현" 명세에서 deviation. 실제 구현은 **손수 작성 재귀하강 파서(zero-dep)**로 대체됨. 근거: MVP 문법(=·!=·~·IN·NOT IN·AND·OR·NOT·ORDER BY)이 재귀하강으로 충분하고, BTS 미니멀 인프라 철학과 일관되며, FR-SR-01 파서 선례가 있다. 새 빌드 의존성(ANTLR 생성코드 통합)을 회피한다. 정본 근거. [ADR docs/decisions/2026-06-25-fr-sr-02-aql-parser-and-bc.md](../decisions/2026-06-25-fr-sr-02-aql-parser-and-bc.md).

### 10.1.1 구문

```
project = PROJ AND status = "In Progress"
assignee = currentUser() AND priority in (High, Highest)
created >= -7d AND resolution is EMPTY
summary ~ "로그인"
labels in (backend, urgent)
"Story Points" > 5
ORDER BY priority DESC, created ASC
```

### 10.1.2 지원 연산자

| 연산자 | 예시 |
|---|---|
| `=`, `!=` | `status = "Done"` |
| `>`, `>=`, `<`, `<=` | `priority >= High` |
| `in`, `not in` | `labels in (a, b)` |
| `~` (포함) | `summary ~ "버그"` |
| `is EMPTY`, `is NOT EMPTY` | `assignee is EMPTY` |
| `AND`, `OR`, `NOT` | 논리 |
| `ORDER BY` | 정렬 |

### 10.1.3 함수

- `currentUser()` - 현재 사용자
- `now()`, `startOfDay()`, `endOfWeek()` - 날짜
- 상대 날짜: `-7d`, `+1w`, `-1M`

### 10.1.4 AST → SQL 변환

> **[Deviation — PR #189]** SDD 원안은 "검색 모듈 내부에서 AST→jOOQ 변환"을 그림으로 명세했으나, BC 격리 원칙에 의해 분할됨. jOOQ 생성 코드(`Tables.ISSUES` 등)는 issue-tracking 모듈 전용이라 search-export-import 모듈이 직접 접근 불가(ArchUnit 차단). 실제 구조. **search 모듈 = 텍스트→AST(파서 책임)**, **issue-tracking 모듈 = AST→jOOQ 변환·실행(IssueSearchPort 구현 책임)**. 계약은 shared-kernel의 `IssueSearchPort`(선례: `BoardIssueLookupPort`). 정본 근거. [ADR docs/decisions/2026-06-25-fr-sr-02-aql-parser-and-bc.md](../decisions/2026-06-25-fr-sr-02-aql-parser-and-bc.md).

```kotlin
// AQL: "project = PROJ AND status = Done"
// AST:
And(
  Eq(Field("project"), Literal("PROJ")),
  Eq(Field("status"), Literal("Done"))
)
// SQL (jOOQ):
dsl.selectFrom(ISSUE)
  .where(PROJECT.KEY.eq("PROJ"))
  .and(STATUS.NAME.eq("Done"))
```

권한 필터는 항상 자동 추가:

```sql
AND issue.project_id IN (
  SELECT project_id FROM permission WHERE user_id = :currentUser AND permission = 'BROWSE'
)
```

## 10.2 한글 검색

PostgreSQL FTS의 한글 처리:

- `to_tsvector('simple', ...)` 사용 (어휘 분석 없이 단순 토큰화)
- pg_trgm으로 부분 문자열 매칭 보강
- 동의어 사전은 application 레이어에서 확장

```sql
WHERE search_vector @@ plainto_tsquery('simple', :query)
   OR summary % :query  -- pg_trgm 유사도
```

## 10.3 필터 저장 (FR-SR-03)

```kotlin
data class SavedFilter(
    val id: Long,
    val ownerId: Long,
    val name: String,
    val aql: String,
    val shareScope: ShareScope,    // PRIVATE / PROJECT / ORG
    val shareTargetIds: List<Long>,
    val isFavorite: Boolean,
)
```

## 10.4 Export (FR-EX)

### 10.4.1 CSV/XLSX

- ≤1만건: 동기 처리 (HTTP 스트리밍)
- >1만건: 비동기 (Worker + MinIO 저장 + 이메일 알림)

Apache POI 사용.

### 10.4.2 출력 컬럼

- 사용자가 선택 (기본: 키, 제목, 상태, 우선순위, 담당자, 생성일)
- 커스텀 필드도 선택 가능

## 10.5 PDF Export (FR-IS-08)

- 단일 이슈 PDF: openhtmltopdf (서버 측)
- 타임라인 PDF: 클라이언트 측 html2canvas + jsPDF

## 10.6 Import (FR-IM)

### 10.6.1 형식

- CSV (Jira 호환)
- JSON (Jira REST API export 호환)

### 10.6.2 매핑 UI

1. 파일 업로드 + 미리보기 (papaparse)
2. 필드 매핑 (Jira `Summary` → Atlas `summary`)
3. 사용자 매핑 (Jira email → Atlas user)
4. 미리 검증 (필수 필드 누락, 잘못된 형식)
5. 비동기 Import 시작 (1만건 단위 청크)
6. 진행률 표시 (WebSocket)

### 10.6.3 Jira 마이그레이션

- 이슈 키 보존: Jira `PROJ-123` → Atlas `PROJ-123`
- 첨부파일 다운로드 → MinIO 업로드
- 댓글, 이력, Worklog 보존
- 사용자 매핑 (Jira email ↔ Atlas LDAP)

## 10.7 다음 챕터

- API 설계 → [11. API 설계](11-api-design.md)
- 마이그레이션 전략 상세 → [15. 마이그레이션](15-migration.md)
