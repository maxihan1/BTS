-- search-export-import BC 대용량 비동기 Import 작업 — import_jobs 테이블 + q_import_jobs pgmq 큐 (FR-IM-01)
--
-- ImportJob aggregate 의 영속 테이블. CSV/JSON 업로드 파일을 PENDING 으로 접수하고,
-- 백그라운드 worker 가 q_import_jobs 큐를 폴링해 RUNNING → COMPLETED/FAILED 로 처리한다.
-- 업로드 원본은 MinIO(source_object_key)에 저장하며, 실패행 로그는 error_log_object_key 에 남긴다.
-- expires_at(완료 +24h) 경과 시 DB+객체를 하드 삭제한다.
--
-- 선례 V602 export_jobs 1:1 미러 — 동일 aggregate 형상(status/progress/expires/timestamptz)에
-- import 전용 컬럼(source_object_key, dry_run, total_rows, succeeded_rows, failed_rows, error_log_object_key)만 조정.
--
-- 소프트 삭제(deleted_at) 없음 — TTL 하드삭제 대상(V602 export_jobs 동형, DATA.md §3 대용량 운영 부산물).
-- 일시적 운영 부산물이라 감사 보존 불요.
--
-- enqueue 방식: ImportJob 영속과 pgmq.send('q_import_jobs', {"importJobId":...}::jsonb) 가 단일 트랜잭션(outbox).
-- 컨슈머: ImportJobWorker(@Scheduled 폴링) — backend-engineer 영역, 본 마이그레이션 범위 외.

-- pgmq extension 보장 — issue-tracking V002 가 도입했으나 모듈별 Flyway namespace 라 명시(V602 패턴).
-- (extension 바이너리는 quay.io/tembo/pg16-pgmq 이미지에 사전 설치 — postgres:16-alpine 불가, ADR 2026-05-22)
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_import_jobs (대용량 Import 작업 디스패치)
SELECT pgmq.create('q_import_jobs');

CREATE TABLE import_jobs (
    id                    UUID PRIMARY KEY,
    project_key           TEXT NOT NULL,                   -- 대상 프로젝트 키
    format                TEXT NOT NULL,                   -- CSV | JSON
    source_object_key     TEXT NOT NULL,                   -- 업로드 원본 MinIO 키
    dry_run               BOOLEAN NOT NULL DEFAULT FALSE,  -- 검증 전용 실행(실제 이슈 생성 안 함)
    requester_user_id     UUID NOT NULL,                   -- 접수자 userId
    status                TEXT NOT NULL DEFAULT 'PENDING', -- PENDING|RUNNING|COMPLETED|FAILED
    progress              INT  NOT NULL DEFAULT 0,         -- 0~100 (%)
    total_rows            BIGINT,                          -- RUNNING 에서 파싱 후 확정
    succeeded_rows        BIGINT NOT NULL DEFAULT 0,       -- 성공 행 누적
    failed_rows           BIGINT NOT NULL DEFAULT 0,       -- 실패 행 누적
    error_code            TEXT,                            -- FAILED 에서 (IMPORT_*)
    error_log_object_key  TEXT,                            -- 실패행 로그 MinIO key, COMPLETED 에서
    expires_at            TIMESTAMPTZ,                     -- COMPLETED 에서 now()+24h (TTL)
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,                     -- RUNNING claim 시각
    completed_at          TIMESTAMPTZ,                     -- 종단(COMPLETED/FAILED) 시각
    CONSTRAINT chk_import_jobs_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    CONSTRAINT chk_import_jobs_format CHECK (format IN ('CSV','JSON'))
);

-- 폴링/소유권 조회 인덱스 — findByIdForRequester / 사용자별 최신 job 목록
CREATE INDEX idx_import_jobs_requester ON import_jobs (requester_user_id, created_at DESC);
-- cleanup 스캔 인덱스 — findExpired(now) (partial: expires_at 가 채워진 COMPLETED job 만)
CREATE INDEX idx_import_jobs_expires ON import_jobs (expires_at) WHERE expires_at IS NOT NULL;
