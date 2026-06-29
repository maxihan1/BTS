-- search-export-import BC 대용량 비동기 Export 작업 — export_jobs 테이블 + q_export_jobs pgmq 큐 (FR-EX-02)
--
-- ExportJob aggregate 의 영속 테이블. 대용량(>1만건) Export 요청을 PENDING 으로 접수하고,
-- 백그라운드 worker 가 q_export_jobs 큐를 폴링해 RUNNING → COMPLETED/FAILED 로 처리한다.
-- 결과 파일은 MinIO(bucket bts-exports)에 저장하며, expires_at(완료 +24h) 경과 시 DB+객체를 하드 삭제한다.
--
-- 소프트 삭제(deleted_at) 없음 — TTL 하드삭제 대상(ADR 2026-06-29-fr-ex-02-async-export-jobs §D5,
-- bulk-operation 하드삭제 근거 동형, DATA.md §3). 일시적 운영 부산물이라 감사 보존 불요.
--
-- enqueue 방식: ExportJob 영속과 pgmq.send('q_export_jobs', {"exportJobId":...}::jsonb) 가 단일 트랜잭션(outbox).
-- 컨슈머: ExportJobWorker(@Scheduled 폴링) — backend-engineer 영역, 본 마이그레이션 범위 외.

-- pgmq extension 보장 — issue-tracking V002 가 도입했으나 모듈별 Flyway namespace 라 명시(V002 패턴).
-- (extension 바이너리는 quay.io/tembo/pg16-pgmq 이미지에 사전 설치 — postgres:16-alpine 불가, ADR 2026-05-22)
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_export_jobs (대용량 Export 작업 디스패치)
SELECT pgmq.create('q_export_jobs');

CREATE TABLE export_jobs (
    id                 UUID PRIMARY KEY,
    project_key        TEXT NOT NULL,                   -- 대상 프로젝트 키
    query              TEXT NOT NULL,                   -- AQL 원문
    format             TEXT NOT NULL,                   -- CSV | XLSX
    columns            TEXT,                            -- 콤마 구분 컬럼 키, NULL=전체
    requester_user_id  UUID NOT NULL,                   -- 접수자(viewer) userId — IssueSearchPort visibility 상속
    status             TEXT NOT NULL DEFAULT 'PENDING', -- PENDING|RUNNING|COMPLETED|FAILED
    progress           INT  NOT NULL DEFAULT 0,         -- 0~100 (%)
    row_count          BIGINT,                          -- RUNNING 에서 count-first 로 확정
    result_object_key  TEXT,                            -- MinIO key, COMPLETED 에서
    error_code         TEXT,                            -- FAILED 에서 (SEARCH_*)
    expires_at         TIMESTAMPTZ,                     -- COMPLETED 에서 now()+24h (TTL)
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,                     -- RUNNING claim 시각
    completed_at       TIMESTAMPTZ,                     -- 종단(COMPLETED/FAILED) 시각
    CONSTRAINT chk_export_jobs_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    CONSTRAINT chk_export_jobs_format CHECK (format IN ('CSV','XLSX'))
);

-- 폴링/소유권 조회 인덱스 — findByIdForRequester / 사용자별 최신 job 목록
CREATE INDEX idx_export_jobs_requester ON export_jobs (requester_user_id, created_at DESC);
-- cleanup 스캔 인덱스 — findExpired(now) (partial: expires_at 가 채워진 COMPLETED job 만)
CREATE INDEX idx_export_jobs_expires ON export_jobs (expires_at) WHERE expires_at IS NOT NULL;
