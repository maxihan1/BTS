# FR-EX-02 — 대용량(>1만건) 비동기 Export (백엔드 D1~D5)

> slug: fr-ex-02-export-async-backend
> type: feature
> agent: backend-engineer
> primary_bc: search-export-import
> 생성: 2026-06-29

## Brief

대용량(>1만건) 비동기 Export. FR-EX-01(동기, PR #203)의 진화판.
사용자 입력: "FR-EX-02 대용량(>1만건) 비동기 Export — pgmq job + 백그라운드 worker + export_jobs 테이블 + 진행률/결과URL TTL + 프론트 진행률/다운로드 UI"

**범위 결정 (Maxi 게이트, 2026-06-29)**: 이번 PR = 백엔드 D1~D5만. 프론트 D6/D7은 별도 PR.
- D1. ExportJob 도메인
- D2. 명세 — 큐 + 진행률 + 결과 URL TTL
- D3. 데이터 모델 — export_jobs(status, progress, result_minio_key, expires_at)
- D4. 백엔드 — pgmq job + 백그라운드 worker
- D5. 백엔드 테스트 — 1만건 시나리오

**classify 교정 메모**: 원판 type=ui(프론트 키워드 끌림) → feature/backend-engineer 교정.

## 도메인 정리

- **BC**: search-export-import (FR-EX-01 동일 본거지, BC 신설 없음)
- **영향 엔티티**: ExportJob (신규 aggregate), export_jobs 테이블 (신규, V602)
- **새 용어**: **ExportJob (Export 작업)** — 대용량 비동기 Export의 영속 aggregate. `status`(PENDING→RUNNING→COMPLETED/FAILED 종단) + `progress` + `result_object_key`(MinIO) + `expires_at`(TTL). pgmq `q_export_jobs`로 백그라운드 처리. (glossary 추가 대기 — Maxi 승인 필요)
- **재사용 자산**:
  - BulkOperation(FR-IS-05, issue-tracking) — pgmq consumer 패턴 1:1 복제 (CAS claim·dead-letter·outbox MANDATORY enqueue·@Transactional 없음·하드삭제 cleanup+Clock 주입)
  - FR-EX-01 export 패키지 — ExportService/Csv·XlsxExportWriter(OutputStream write)/ExportColumn/ExportFormat/ExportCellSanitizer/IssueSearchPort(BROWSE+visibility 구조적 상속)
  - issue-tracking MinioStorageConfig 패턴 — search 모듈 자체 MinioClient 복제 (BC 격리)
- **핵심 아키텍처 결정** (ADR 상세):
  - D4. **MinIO 접근 = search 모듈 자체 클라이언트** (io.minio 의존성 추가, bucket `bts-exports`). shared-kernel 포트 추출 기각 — MinIO는 공유 인프라라 각 BC 자체 클라이언트가 자연스럽고, 포트화는 cross-BC 호출+배포조립(부재) 강제.
  - D5. 결과 TTL = 하드삭제 (DB + MinIO 객체). 일시적 운영 부산물 (bulk-operation 하드삭제 근거 동형).
  - D6. job 소유권 — 본인 job만 조회/다운로드 (비소유 404 존재 은닉).
  - D7. 비동기 행 상한 + 스트리밍(SXSSF) 전략 → spec D2 이연.
- **기존 결정 충돌**: 없음 (FR-EX-01 ADR §D5가 `/search/export-jobs`·export_jobs·SXSSF를 명시적으로 예고).
- **관련 ADR**: [docs/decisions/2026-06-29-fr-ex-02-async-export-jobs.md](../decisions/2026-06-29-fr-ex-02-async-export-jobs.md) (생성됨)
- **grill-with-docs 생략 근거**: BulkOperation 선례 1:1 매핑 + 새 용어 1개로 도메인 명확. 명확한 백엔드 작업에 대화형 스킬 과함 (메모리: bts-spec office-hours mismatch / bts-review-plan autoplan overkill). 게이트 1에서 일괄 검토.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
