-- 이슈 해결 상태(resolution) 마스터 테이블 + 표준 5종 seed + issues.resolution_id 컬럼 — FR-IS-07. DATA.md §3 소프트 삭제 + §4 TIMESTAMPTZ 준수

-- resolutions 테이블
-- 이슈가 "왜 종료되었는가"를 표현하는 해결 상태(Fixed, Won't Fix 등). is_standard=true 인 5개는 시스템 표준 — 삭제/변경 비권장.
-- deleted_at: 소프트 삭제 컬럼 (DATA.md §3) — NULL=활성, NOT NULL=삭제됨.
CREATE TABLE resolutions (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- key: URL-safe 소문자 식별자 (예: fixed, wontfix). 활성 기준 유일.
    key            TEXT         NOT NULL UNIQUE,
    name           TEXT         NOT NULL,
    description    TEXT         NULL,
    -- display_order: 목록 정렬 순서. 표준 5종은 1~5.
    display_order  INT          NOT NULL,
    -- is_standard: 시스템 기본 제공 해결 상태 여부. true 인 row 는 변경/삭제 비권장.
    is_standard    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  resolutions               IS '이슈 해결 상태 마스터. is_standard=true 는 시스템 표준 5종 (FR-IS-07).';
COMMENT ON COLUMN resolutions.key           IS 'URL-safe 소문자 식별자 (예: fixed, wontfix). UNIQUE.';
COMMENT ON COLUMN resolutions.display_order IS '목록 정렬 순서. 표준 5종은 1~5.';
COMMENT ON COLUMN resolutions.is_standard   IS 'true = 시스템 표준 해결 상태 — 삭제/변경 비권장.';
COMMENT ON COLUMN resolutions.deleted_at    IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3).';

-- 표준 5종 seed (display_order 오름차순).
-- id 는 Zod v4 UUID 형식 고정값 — 프론트 픽스처/E2E 가 재사용하는 결정적 식별자 (메모리 zod-v4-uuid-fixture-strictness).
-- 3번째 그룹 4로 시작, 4번째 그룹 8~b 로 시작.
INSERT INTO resolutions (id, key, name, description, display_order, is_standard) VALUES
    ('00000000-0000-4000-8000-000000000001', 'fixed',           'Fixed',            '수정 완료',              1, true),
    ('00000000-0000-4000-8000-000000000002', 'wontfix',         'Won''t Fix',       '수정하지 않기로 결정',   2, true),
    ('00000000-0000-4000-8000-000000000003', 'duplicate',       'Duplicate',        '중복 이슈',              3, true),
    ('00000000-0000-4000-8000-000000000004', 'cannotreproduce', 'Cannot Reproduce', '재현 불가',              4, true),
    ('00000000-0000-4000-8000-000000000005', 'done',            'Done',             '완료',                   5, true);

-- issues.resolution_id 컬럼 추가.
-- BC 격리: resolutions 는 같은 BC(issue-tracking) 이지만, 종결 시점에만 설정되는 선택 참조이며
-- 향후 cross-BC 워크플로우 결선과의 결합을 피하기 위해 FK 제약을 의도적으로 미적용한다.
-- 존재 검증은 ApplicationService 가 담당 (issues.reporter_id / current_state_key 선례와 동일 정책).
ALTER TABLE issues ADD COLUMN resolution_id UUID NULL;
COMMENT ON COLUMN issues.resolution_id IS
    'resolutions.id 대응 해결 상태. 종결 시점에만 설정 (선택). BC 격리로 FK 미적용 — ApplicationService 가 존재 guard.';
