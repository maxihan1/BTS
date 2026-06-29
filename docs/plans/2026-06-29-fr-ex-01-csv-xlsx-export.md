# FR-EX-01 — 필터 결과 CSV/XLSX Export

> slug: fr-ex-01-csv-xlsx-export
> type: api
> agent: backend-engineer
> 생성: 2026-06-29

## Brief

FR-EX-01 (search-export-import BC, SDD §3.1, 필수). 필터(FR-SR-01)/AQL(FR-SR-02) 검색 결과를
CSV(UTF-8 BOM 한글 인코딩) + XLSX(Apache POI)로 **동기** 다운로드.
`POST /api/v1/exports`. 선행 §2.1 FR-SR-01 + §2.2 FR-SR-02 모두 완료.
대용량(>1만건) 비동기 Export는 FR-EX-02로 분리 (이번 범위 아님).

product D단계.
- D1. 도메인 — ExportRequest (backend-engineer)
- D2. 명세 — 필드 선택 + 한글 인코딩 (UTF-8 BOM) (backend-engineer)
- D3. 데이터 모델 — (활용) (db-engineer)
- D4. 백엔드 — POST /api/v1/exports (CSV + Apache POI XLSX) (backend-engineer)
- D5. 백엔드 테스트 — Excel 검증 (backend-engineer)
- D6. 프론트 UI — Export 다이얼로그 + 진행률 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리 (← /bts-domain 채움)

- **BC**: search-export-import (물리·논리 모두). FR-SR-02 ADR D2가 "search-export-import = Export/Import/REST API FR의 본거지"로 예고 → BC 신설 없음, 기존 모듈 확장.
- **데이터 접근**: `IssueSearchPort`(shared-kernel) → issue-tracking `IssueSearchAdapter`. search 모듈은 jOOQ 직접 접근 불가(ArchUnit BC 격리). visibility 보안 술어 + BROWSE 권한은 어댑터가 이미 자동 적용 → export 경로도 우회 불가 자동 상속.
- **영향 엔티티/타입**:
  - 신규(search-export-import): `ExportRequest`(VO — projectKey·AQL query·format·선택 컬럼), `ExportFormat`(enum CSV/XLSX), `ExportController`(`POST /api/v1/exports`), CSV writer + XLSX writer(Apache POI).
  - 재사용: `IssueSearchPort`/`IssueSearchQuery`/`IssueSearchHit`(9필드, **무변경**), `AqlParser`/`AqlLexer`(AQL→AST). issue-tracking·shared-kernel **변경 0**(cross-BC 안 건드림).
- **새 용어**: Export(검색 결과를 파일로 내보내기), ExportFormat(CSV/XLSX). glossary 추가 후보.
- **Maxi 결정(2026-06-29 도메인 게이트, 4개 갈림길)**:
  1. **입력 경로** = AQL 쿼리 기반만. `POST /api/v1/exports` body `{projectKey, query(AQL), format, columns?}`. 필터(GET /issues?filter=)는 미지원(assignee/component는 AQL MVP 필드 부재 → FR-SR 후속 확장으로 해결).
  2. **포맷** = CSV + XLSX 둘 다. **Apache POI 신규 의존성 도입 승인**(XLSX는 손수 구현 비현실적, POI가 표준). CSV는 라이브러리 없이 손수(UTF-8 BOM).
  3. **컬럼** = `IssueSearchHit` 9필드 고정(key, summary, typeKey, currentStateKey, assigneeId, priority, priorityName, projectKey, updatedAt). 포트 무변경. product D2 "필드 선택" = 이 9개 중 부분 선택으로 해석. assigneeId는 UUID(이름 해석은 후속).
  4. **행 상한** = 1만건(동기). 초과 시 명시 에러 + FR-EX-02 비동기 export 안내. IssueSearchPort 페이지 순회로 전체 fetch.
- **새 외부 의존성**: `org.apache.poi:poi-ooxml` (DEVELOPMENT.md §외부 의존성 — Maxi 승인 완료). CSV는 zero-dep.
- **기존 결정 충돌**: 없음. FR-SR-02 ADR이 본 FR을 예고. BC 격리·포트 패턴 그대로 계승.
- **관련 ADR**: [docs/decisions/2026-06-29-fr-ex-01-csv-xlsx-export.md](../decisions/2026-06-29-fr-ex-01-csv-xlsx-export.md) (생성됨). 선행 [2026-06-25-fr-sr-02-aql-parser-and-bc.md](../decisions/2026-06-25-fr-sr-02-aql-parser-and-bc.md).

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
