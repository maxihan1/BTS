-- 이슈 첨부 파일 메타데이터 테이블 — FR-AC-01. 바이너리는 MinIO, 메타만 DB. 첨부는 하드 삭제라 deleted_at 없음 (ADR 2026-06-15-fr-ac-01-attachment-storage §5).

-- ── issue_attachments: 이슈에 업로드된 파일의 메타데이터 ──────────────────────────
-- 파일 바이너리는 MinIO(오브젝트 스토리지)에 저장하고, 여기에는 메타데이터만 보관한다 (ADR §1).
-- 소프트 삭제 미적용: 첨부는 대용량 바이너리 + 약한 외부 참조라 삭제 = DB row + MinIO 객체 즉시 제거 (ADR §5).
--   domain "소프트 삭제 우선" 규칙의 의도적 deviation — 첨부 도메인 특성에 한정. deleted_at 컬럼 없음.
-- issue_id FK + ON DELETE CASCADE: 같은 BC(issue-tracking) 내부 테이블이라 issues 실 FK. 이슈가 하드 삭제될
--   경로는 현재 없으나(prod 는 소프트 삭제) 무결성상 CASCADE 표기 — 하드 삭제 경로(테스트 cleanup 등)에서
--   고아 첨부 메타 행을 자동 정리해 FK 위반을 막는다.
-- uploaded_by: identity-access BC users.id 대응. BC 격리 원칙으로 FK 미적용 — ApplicationService 가 guard.
-- SDD §05.7 은 id/issue_id 를 BIGINT 로 표기했으나 실제 issues.id 가 UUID(V001)라 UUID PK/FK 로 구현 (ADR deviation).
CREATE TABLE issue_attachments (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id     UUID         NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    filename     VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    storage_key  VARCHAR(500) NOT NULL,
    uploaded_by  UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  issue_attachments              IS '이슈 첨부 파일 메타데이터. 바이너리는 MinIO, 메타만 DB. 하드 삭제 — deleted_at 없음 (FR-AC-01).';
COMMENT ON COLUMN issue_attachments.id           IS '첨부 식별자 (UUID). gen_random_uuid() 기본값.';
COMMENT ON COLUMN issue_attachments.issue_id     IS '대상 이슈 (issues.id). 같은 BC 라 실 FK + ON DELETE CASCADE.';
COMMENT ON COLUMN issue_attachments.filename     IS '원본 파일명. 다운로드 시 Content-Disposition 에 사용.';
COMMENT ON COLUMN issue_attachments.content_type IS 'MIME 타입 (예: image/png). 다운로드 시 Content-Type 에 사용.';
COMMENT ON COLUMN issue_attachments.size_bytes   IS '파일 크기 (바이트). 다운로드 시 Content-Length 에 사용.';
COMMENT ON COLUMN issue_attachments.storage_key  IS 'MinIO 객체 키. 외부 비노출 (응답 DTO 제외).';
COMMENT ON COLUMN issue_attachments.uploaded_by  IS '업로더 사용자 ID (identity-access users.id 대응). BC 격리로 FK 미적용.';
COMMENT ON COLUMN issue_attachments.created_at   IS '업로드 시각. TIMESTAMPTZ (DATA.md §4).';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함).
-- issue_id 단독 조회(이슈별 첨부 목록)에 쓰이므로 인덱스 필수.
CREATE INDEX idx_issue_attachments_issue_id ON issue_attachments(issue_id);
