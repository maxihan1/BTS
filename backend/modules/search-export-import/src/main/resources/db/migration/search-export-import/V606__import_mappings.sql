-- search-export-import BC Import 필드 매핑 — import_mappings 테이블 + chk_import_jobs_status 에 AWAITING_MAPPING 추가 (FR-IM-02)
--
-- FR-IM-02(매핑 UI) 도입으로 Import 흐름이 analyze → map → run 2단계가 된다.
-- 파일 분석 직후 job 은 AWAITING_MAPPING 상태로 접수되고, 사용자가 소스 필드 ↔ 대상 필드 매핑을
-- 확정하면 PENDING 으로 전이해 워커가 처리한다. 확정한 매핑은 import_mappings 에 job 당 여러 행으로 저장한다.
--
-- (1) chk_import_jobs_status 확장 — 기존 4-상태(PENDING/RUNNING/COMPLETED/FAILED) 앞에 AWAITING_MAPPING 을
--     추가한 5-상태로 교체한다. CHECK 는 이름이 같은 제약을 재정의할 수 없어 DROP 후 재-ADD 한다
--     (수동 ALTER 가 아니라 Flyway 마이그레이션 내부의 스키마 변경 — DATA §1.3 준수).
-- (2) import_mappings 신규 테이블 — (import_job_id, source_field) 복합 PK. 한 job 의 소스 필드 하나는
--     대상 필드 하나에만 매핑되므로 source_field 가 job 범위에서 유일하다.
--
-- FK import_job_id → import_jobs(id) ON DELETE CASCADE — import_mappings 는 job 종속 하위 테이블이다.
-- CleanupWorker 가 만료 job 을 하드삭제할 때(V604 의 TTL 하드삭제 대상) 매핑 행이 고아로 남지 않도록
-- 부모 삭제 시 동반 삭제한다 (join-table FK cascade, ADR 2026-07-03-fr-im-02-import-mapping).
--
-- 소프트 삭제(deleted_at) 없음 — 부모 import_jobs 와 생명주기를 공유하는 하위 테이블이라
-- 부모 하드삭제 시 함께 사라진다(V604 import_jobs 동형, DATA §3 대용량 운영 부산물).
--
-- FK 인덱스: 복합 PK 의 선행 컬럼이 import_job_id 라 `WHERE import_job_id = ?`(매핑 조회·CASCADE 삭제)가
-- PK 인덱스를 그대로 사용한다. 별도 FK 인덱스는 불필요(중복 인덱스 회피).

-- (1) status CHECK 에 AWAITING_MAPPING 추가 — 이름이 같은 CHECK 재정의 불가라 DROP 후 재-ADD.
ALTER TABLE import_jobs DROP CONSTRAINT chk_import_jobs_status;
ALTER TABLE import_jobs ADD CONSTRAINT chk_import_jobs_status
    CHECK (status IN ('AWAITING_MAPPING','PENDING','RUNNING','COMPLETED','FAILED'));

-- (2) 확정 매핑 저장 테이블 — job 당 (소스 필드 → 대상 필드) 다행.
CREATE TABLE import_mappings (
    import_job_id  UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE, -- 부모 job (하드삭제 시 CASCADE)
    source_field   TEXT NOT NULL,                                             -- CSV 헤더 / JSON 키 원문
    target_field   TEXT NOT NULL,                                             -- BTS 대상 필드 key 또는 IGNORE 센티널
    PRIMARY KEY (import_job_id, source_field)                                 -- 소스 필드는 job 범위에서 유일
);
