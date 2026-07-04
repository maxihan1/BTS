-- search-export-import BC Import 사용자 매핑 — import_user_mappings 테이블 (FR-IM-02 PR-B)
--
-- FR-IM-02(매핑 UI) 사용자 매핑 단계 — CSV/JSON 소스의 담당자·보고자 식별자(이메일/이름 등)를
-- BTS 사용자(userId)로 확정 매핑한 결과를 job 당 여러 행으로 저장한다. 미해결(unmapped) 식별자는
-- target_user_id = NULL 로 남겨 두고, Import 실행 시 앱 계층에서 처리 정책을 적용한다.
-- 한 소스 식별자는 job 범위에서 대상 하나에만 매핑되므로 (import_job_id, source_identifier) 가 복합 PK 다.
--
-- FK import_job_id → import_jobs(id) ON DELETE CASCADE — import_user_mappings 는 job 종속 하위 테이블이다.
-- CleanupWorker 가 만료 job 을 하드삭제할 때(V604 TTL 하드삭제) 매핑 행이 고아로 남지 않도록 동반 삭제한다
-- (join-table FK cascade, V606 import_mappings 동형).
--
-- target_user_id 는 FK 미적용 — 대상 users 는 cross-BC(identity-access BC 소유)라 search-export-import 에서
-- DB 외래 키로 참조하지 않는다. 존재성은 앱 계층 UserLookupPort 로 검증한다(favorites 선례 — cross-BC 대상은
-- DB FK 대신 앱 계층 검증). BC 경계를 넘는 물리 FK 는 배포·마이그레이션 결합을 만들어 금지한다.
--
-- 소프트 삭제(deleted_at) 없음 — 부모 import_jobs 와 생명주기를 공유하는 하위 테이블이라
-- 부모 하드삭제 시 함께 사라진다(V606 import_mappings 동형, DATA §3 대용량 운영 부산물).
--
-- FK 인덱스: 복합 PK 선행 컬럼이 import_job_id 라 `WHERE import_job_id = ?`(매핑 조회·CASCADE 삭제)가
-- PK 인덱스를 그대로 사용한다. 별도 FK 인덱스는 불필요(중복 인덱스 회피, V606 동형).

CREATE TABLE import_user_mappings (
    import_job_id      UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE, -- 부모 job (하드삭제 시 CASCADE)
    source_identifier  TEXT NOT NULL,                                              -- 소스 사용자 식별자(이메일/이름 등 원문)
    target_user_id     UUID NULL,                                                  -- 매핑된 BTS userId (미해결 시 NULL, cross-BC FK 미적용)
    PRIMARY KEY (import_job_id, source_identifier)                                 -- 소스 식별자는 job 범위에서 유일
);
