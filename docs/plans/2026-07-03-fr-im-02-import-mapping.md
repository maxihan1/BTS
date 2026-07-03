# FR-IM-02 — Import 매핑 UI (필드/사용자 매핑)

> slug: fr-im-02-import-mapping
> type: api (풀스택 feature — 백엔드 검증 API + 프론트 매핑 마법사)
> agent: backend-engineer (프론트 task는 frontend-engineer, E2E는 qa-engineer)
> 생성: 2026-07-03

## Brief

FR-IM-02 Import 매핑 UI (필드/사용자 매핑). 선행 FR-IM-01(CSV/JSON Import, 전체 완료).
소스(CSV/JSON) 필드를 BTS 대상 필드에 매핑 + 소스 사용자를 BTS 사용자에 매핑 + 미매핑 처리.
매핑 검증 API + 매핑 마법사(다단계) 프론트.

- BC. search-export-import (물리 구현 위치는 domain 단계에서 확정 — FR-IM-01은 논리 search-export-import / 물리 자체 모듈 or issue-tracking 재사용 패턴 확인 필요)
- Plan slug (product). search/import-mapping
- product D단계.
  - D1. 도메인 — ImportMapping
  - D2. 명세 — 필드 매핑 + 사용자 매핑 + 미매핑 처리
  - D3. 데이터 모델 — import_mappings(import_job_id, source_field, target_field)
  - D4. 백엔드 — 매핑 검증 API
  - D5. 백엔드 테스트
  - D6. 프론트 UI — 매핑 마법사 (다단계)
  - D7. E2E

## 도메인 정리

- **BC**. search-export-import (물리 구현 `com.bts.search.imports`, FR-IM-01과 동일 모듈)
- **작업 범위 (이번 /bts 실행)**. **PR-A = 기반 + 필드 매핑**. 백엔드는 순차 에픽 3-PR로 분할 (Maxi 결정, 사용자/값 매핑 확장으로 범위 증가).
  - **PR-A (이번)**. V606(status CHECK + import_mappings), AWAITING_MAPPING, analyze, 매핑-aware CSV 파서, 필드 매핑 validate/confirm, 워커 통합.
  - **PR-B (후속)**. 사용자 매핑 — 전 작성자 필드(reporter/assignee + 댓글/worklog/첨부/changelog 작성자). import_user_mappings + distinct 수집 + 프로세서 해석 + VO authorUserId.
  - **PR-C (후속)**. 값 매핑 — status/type/priority. import_value_mappings + distinct 값 수집 + 프로세서 값 치환.
  - **프론트 D6/D7 (후속)**. 다단계 매핑 마법사 + E2E.

### Maxi 아키텍처 결정 (2026-07-03, AskUserQuestion 4건)

1. **2단계 흐름** — analyze(분석)→매핑→실행. 기존 즉시-업로드(`POST /api/v1/imports`, canonical 자동매핑)는 하위호환 유지. 새 매핑 경로는 `ImportJob`에 `AWAITING_MAPPING` 상태 추가.
2. **명시적 사용자 매핑 지속화** — 파일에서 distinct 리포터/담당자 식별자 수집→이메일 자동해석→사용자가 각 항목을 BTS 사용자로 확정/재지정→저장→워커가 사용.
3. **CSV 자유 필드매핑 + JSON canonical** — CSV는 임의 헤더 자유 매핑(핵심 가치), JSON은 Jira REST export 고정 구조라 canonical 유지(필드 매핑 대상 제외). 사용자 매핑은 CSV+JSON 공통.
4. **PR 분할** — 백엔드 먼저(PR1) → 프론트 후속(PR2). FR-IM-01 선례.

### 새 유비쿼터스 언어 (glossary 추가 후보 — Maxi 승인 대기)

| 용어 | 정의 |
|---|---|
| ImportMapping (Import 매핑) | 한 Import 작업에서 소스 파일을 어떻게 해석할지 정의한 구성. 필드 매핑 + 사용자 매핑. `import_jobs` 자식. |
| 소스 필드 (Source field) | 업로드 파일에서 감지된 컬럼(CSV 헤더). analyze 단계 산출물. |
| 대상 필드 (Target field) | 소스가 매핑될 수 있는 BTS 이슈 필드 카탈로그(summary·description·type·priority·reporter·assignee·labels·component·status·fixVersion·affectsVersion). |
| 필드 매핑 (Field mapping) | 소스 필드 → 대상 필드 대응(또는 무시/IGNORE). |
| 사용자 매핑 (User mapping) | 소스 사용자 식별자(이메일/이름) → BTS 사용자 UUID. |
| 미매핑 처리 (Unmapped handling) | 매핑 안 된 소스 필드는 무시. 미해석 사용자는 기존 FR-IM-01 폴백(reporter→requester, assignee→미배정). |
| 분석 (Analyze) | 업로드 파일을 파싱해 소스 필드·distinct 사용자 식별자·샘플 행을 감지하는 단계. |
| AWAITING_MAPPING | 분석 완료 후 매핑 대기 중인 `ImportJob` 상태(PENDING 이전). |

### 영향 엔티티/자산

- **신규 도메인**. `ImportMapping`(필드 매핑 컬렉션 + 사용자 매핑 컬렉션 — `ImportJob` aggregate 소유 또는 job-keyed 소형 aggregate).
- **enum 확장**. `ImportJobStatus`에 `AWAITING_MAPPING` 추가. (Kotlin enum 카운트 가드 없음 — 안전. **단 DB CHECK 제약 `chk_import_jobs_status` 확장 필수** + `SchemaMigrationImportTest` 검증 동반.)
- **신규 데이터 모델**. `import_mappings(import_job_id, source_field, target_field)` (필드 매핑) + **`import_user_mappings(import_job_id, source_identifier, target_user_id)`** (사용자 매핑 — product 문서 단일 테이블 스케치의 확장, 아래 §스펙 deviation 기록).
- **파서 확장**. `ImportRowParser`를 매핑-aware로 — CSV는 하드코딩 `HEADER_*` 대신 필드 매핑으로 컬럼 해석. JSON은 canonical 유지.
- **다음 V번호**. V606 (전 모듈 최대 605).

### 순서 의존 (중요)

사용자 식별자는 reporter/assignee로 **매핑된 컬럼 안에** 존재한다. 따라서 마법사/API 순서는 **필드 매핑 → (그 매핑 기준) distinct 사용자 수집 → 사용자 매핑**. 사용자 식별자 수집은 필드 매핑을 파라미터로 받는 2차 분석이다.

### 기존 결정 충돌

- 없음. FR-IM-01 ADR(`2026-07-02-fr-im-01-csv-json-import.md`)이 이 확장을 명시적으로 예정(`ImportRowParser` KDoc L36, `IssueImportCommand` KDoc L13). 커맨드 객체 설계로 포트 시그니처 변경 없이 필드 추가 가능.

### 스펙 deviation (product 문서 대비 — 전수 동기화 필요)

- product §4.2 D3은 `import_mappings(import_job_id, source_field, target_field)` **단일 테이블**만 명시. 사용자 매핑 지속화(Maxi 결정 2)를 위해 **`import_user_mappings` 테이블 추가**. → product 문서 D3 본문 갱신 + fr-index 무영향(FR 수 불변).
- `ImportJobStatus`에 `AWAITING_MAPPING` 신규 상태 → 흐름 확장은 SDD 10.6 참조 필요 시 반영.

### 관련 ADR

- [docs/decisions/2026-07-03-fr-im-02-import-mapping.md](../decisions/2026-07-03-fr-im-02-import-mapping.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-03-fr-im-02-import-mapping.md](../specs/2026-07-03-fr-im-02-import-mapping.md)

**PR-A 핵심 (기반 + 필드 매핑)**.
- 2단계 흐름: `POST /imports/analyze`(persist+AWAITING_MAPPING+헤더/샘플 감지) → `POST /imports/{id}/mapping/validate`(필드매핑 검증) → `POST /imports/{id}/mapping`(저장+PENDING 전이+enqueue).
- 매핑-aware CSV 파서(임의 헤더→대상 필드, 매핑 없으면 canonical 폴백). JSON canonical 유지.
- V606: status CHECK에 AWAITING_MAPPING 추가 + import_mappings 테이블. 워커는 PENDING만 클레임.
- 대상 필드 카탈로그 11개 + IGNORE. 에러 4종·경고 1종.
- 기존 즉시-업로드 경로 회귀 0.

## Brainstorming Check

✅ 통과 (자기-비평 1회). 수정가능 갭 6건 스펙 반영. 범위 경계 2건(전 작성자 사용자매핑·값 매핑)은 Maxi 결정으로 PR-B/PR-C 분리 → 이번 PR-A는 필드 매핑에 집중.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
