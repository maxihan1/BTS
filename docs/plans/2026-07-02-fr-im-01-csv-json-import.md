# FR-IM-01 — CSV/JSON Import (Jira 마이그레이션)

> slug: fr-im-01-csv-json-import
> type: feature
> agent: backend-engineer
> 생성: 2026-07-02
> BC: search-export-import (§4.1)

## Brief

FR-IM-01 CSV/JSON Import (Jira 마이그레이션). search-export-import BC §4.1, 우선순위 필수.
선행: issue-tracking §2~§3 (이슈/컴포넌트/버전 작성 API) — 완료.
작업 범위 D1~D7:
- D1. 도메인 — ImportJob (backend-engineer)
- D2. 명세 — CSV/JSON 파싱 + 트랜잭션 정책 + dry-run (backend-engineer)
- D3. 데이터 모델 — import_jobs(status, error_log_minio_key) (db-engineer)
- D4. 백엔드 — POST /api/v1/imports + 백그라운드 worker (backend-engineer)
- D5. 백엔드 테스트 — Jira CSV 샘플 (backend-engineer)
- D6. 프론트 UI — 파일 업로드 + 진행률 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

강력한 선례: FR-EX-02(비동기 Export) — pgmq job + MinIO + 폴링 worker 패턴의 역방향.

classify 정정: 'Jira 마이그레이션' → DB migration 오인(type=migration/db-engineer)을 feature/backend-engineer로 정정.

## 도메인 정리

- **BC**. search-export-import (물리 모듈 동일, 새 BC 신설 0). FR-EX-02(비동기 Export)의 역방향 미러.
- **패키지**. `com.bts.search.imports` (Kotlin `import`는 예약어 → 복수형. 엔드포인트 `POST /api/v1/imports`와 정합).
- **신규 엔티티**. `ImportJob` (Aggregate Root, `ExportJob` 거울상) — status(PENDING/RUNNING/COMPLETED/FAILED)·progress(0~100)·sourceObjectKey(업로드 원본 MinIO 키)·errorLogObjectKey(실패행 로그)·totalRows/succeededRows/failedRows·requesterUserId·projectKey·format(CSV/JSON)·dryRun·expiresAt(TTL)·created/started/completedAt.

### cross-BC write 포트 (핵심 결정 — ADR)

- Export는 이슈를 **읽음**(`IssueSearchPort`, shared-kernel, issue-tracking 어댑터). Import는 이슈를 **써야** 함.
- **BTS 최초의 search→issue-tracking write 경로**. 기존 shared-kernel 포트는 전부 read/lookup.
- shared-kernel 신규 `IssueImportPort` 인터페이스 → issue-tracking `IssueImportAdapter` 구현. `IssueSearchPort` 패턴 1:1 미러(default fail-safe 구현 포함).
- **권한 게이트는 issue-tracking 안에 유지**. 어댑터가 `IssueApplicationService.createIssue(actor=requester, ...)`를 호출 → CREATE_ISSUE 권한·워크플로우 시작상태·이슈키 발급·이벤트 발행 전부 기존 경로 재사용. search BC는 권한을 알지 못함(우회 불가).
- **트랜잭션 경계**. 행별 best-effort — 각 createIssue가 issue-tracking 안에서 자체 @Transactional. 한 행 실패가 다른 행에 영향 없음(FR-IS-05 BulkOperation 선례 동형).

### 사용자 매핑 (Maxi 결정 — 이메일 자동매칭+폴백)

- Jira CSV의 reporter/assignee(이메일) → `UserLookupPort` 이메일 조회로 userId 해석.
- 미매칭 시 reporter=import 실행자(requester), assignee=미할당(null).
- **`UserLookupPort` 확장 필요** — 현재 `exists`/username 조회만 있음. 신규 `resolveByEmails(emails): Map<String,UUID>`를 **default 메서드**로 추가(기존 ~35개 인라인 구현 안 깨짐 — memory: 공유 인터페이스 확장은 default fail-safe). identity-access `UserLookupAdapter`가 실제 구현.
- 명시적 필드/사용자 매핑 UI는 **FR-IM-02** 범위. FR-IM-01은 고정 기본 매핑.

### Maxi 결정 요약 (게이트 전 확정)

| 결정 | 채택 |
|---|---|
| 트랜잭션 정책 | 행별 best-effort (실패행→에러 로그 MinIO, 나머지 계속) |
| 사용자 매핑 | 이메일 자동매칭 → 폴백(reporter=실행자, assignee=null) |
| dry-run | 선택 옵션(`dryRun=true` → 파싱+검증만, 생성 0, 리포트 반환) |
| PR 분할 | 백엔드(D1~D5) 먼저 → **이번 PR은 백엔드 전용**, D6/D7 후속 PR |

### 재사용 자산 (FR-EX-02 1:1 미러)

- pgmq worker(`ExportJobWorker` → `ImportJobWorker`, CAS claim·dead-letter·outbox enqueue·VT/stale 정합)
- MinIO 자체 클라이언트 빈 분리(`exportMinioClient` → `importMinioClient`, io.minio 기승인, BC 격리). 버킷 `bts-imports`(업로드 원본 + 에러 로그).
- `@Transactional` 밖 process()(분 단위 I/O), CSV 파서(RFC 4180 — FR-EX-01 자산 참고), POI/CSV 의존성 이미 모듈 존재.
- **차이점**. Export=파일 생성/다운로드. Import=multipart 파일 업로드→MinIO 저장→enqueue→worker 파싱→행별 createIssue.

### 데이터 모델

- `import_jobs` 테이블(V604 예정 — V600~603 사용, 다음 번호. **머지 직전 재확인** — memory: V번호 동시 브랜치 충돌) + pgmq 큐 `q_import_jobs`.
- 소프트 삭제 없음(TTL 하드삭제, export_jobs 동형).

### 신규 용어 (glossary 추가 후보 — Maxi 승인 후)

- **Import** — CSV/JSON 파일을 파싱해 이슈/컴포넌트/버전을 대량 생성(Jira 마이그레이션). Export의 역방향.
- **ImportJob** — 비동기 Import 작업 단위. pgmq worker가 PENDING→RUNNING→COMPLETED/FAILED 처리. 행별 best-effort(부분 실패 허용).
- **dry-run** — 실제 생성 없이 파싱+검증만 수행해 결과를 미리 보는 검증 실행 모드.

- **기존 결정 충돌**. 없음.
- **관련 ADR**. `docs/decisions/2026-07-02-fr-im-01-csv-json-import.md` (생성). FR-EX-02 ADR·IssueSearchPort ADR(2026-06-01) 계승.
- **선행 조건**. issue-tracking §2~§3(이슈/컴포넌트/버전 작성 API) 완료 확인 ✓.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
