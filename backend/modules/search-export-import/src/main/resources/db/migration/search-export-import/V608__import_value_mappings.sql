-- search-export-import BC Import 값 매핑 — import_value_mappings 테이블 (FR-IM-02 PR-C)
--
-- FR-IM-02(매핑 UI) 값매핑 단계 — CSV/JSON 소스의 상태/타입/우선순위 원문 값(예: "Open", "Bug", "High")을
-- BTS 대상 값(예: "OPEN", "BUG", "HIGH")으로 확정 매핑한 결과를 job 당 여러 행으로 저장한다.
-- 한 job 의 (대상 필드, 소스 값) 조합은 대상 값 하나에만 매핑되므로
-- (import_job_id, target_field, source_value) 가 복합 PK 다.
--
-- target_value 는 NOT NULL — V607 import_user_mappings 의 target_user_id NULL 허용(미해결 사용자는 NULL 로
-- 남김)과 다른 비대칭이다. 값 매핑 행은 항상 매핑이 확정된 상태로만 저장되며, 미해결(unmapped) 값은
-- 행 자체를 만들지 않고 Import 실행 시 앱 계층에서 원본 값 유지(폴백)로 처리한다.
--
-- FK import_job_id → import_jobs(id) ON DELETE CASCADE — import_value_mappings 는 job 종속 하위 테이블이다.
-- CleanupWorker 가 만료 job 을 하드삭제할 때(V604 TTL 하드삭제) 매핑 행이 고아로 남지 않도록 동반 삭제한다
-- (join-table FK cascade, V606 import_mappings / V607 import_user_mappings 동형).
--
-- 소프트 삭제(deleted_at) 없음 — 부모 import_jobs 와 생명주기를 공유하는 하위 테이블이라
-- 부모 하드삭제 시 함께 사라진다(V606/V607 동형, DATA §3 대용량 운영 부산물).
--
-- FK 인덱스: 복합 PK 선행 컬럼이 import_job_id 라 `WHERE import_job_id = ?`(매핑 조회·CASCADE 삭제)가
-- PK 인덱스를 그대로 사용한다. 별도 FK 인덱스는 불필요(중복 인덱스 회피, V606/V607 동형).
--
-- target_value 는 FK 미적용 — 값은 문자열(상태 키/타입 키/우선순위 이름)이라 참조 대상 테이블이 없다.
-- FK 참여 컬럼은 import_job_id 뿐이다.

CREATE TABLE import_value_mappings (
    import_job_id  UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE, -- 부모 job (하드삭제 시 CASCADE)
    target_field   TEXT NOT NULL,                                              -- 'STATUS' | 'TYPE' | 'PRIORITY'
    source_value   TEXT NOT NULL,                                              -- 소스 값 원문 (예: "Open")
    target_value   TEXT NOT NULL,                                              -- 매핑된 BTS 대상 값 (미해결 행 없음)
    PRIMARY KEY (import_job_id, target_field, source_value),                   -- (대상 필드, 소스 값)은 job 범위에서 유일
    CONSTRAINT chk_import_value_mappings_field
        CHECK (target_field IN ('STATUS','TYPE','PRIORITY'))
);
