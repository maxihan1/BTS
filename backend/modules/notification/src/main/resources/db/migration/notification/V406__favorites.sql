-- 사용자별 즐겨찾기(북마크) 테이블 (FR-UX-02 V406)

-- ── favorites ──────────────────────────────────────────────────────────────────
-- 사용자 개인 즐겨찾기. (user_id, target_type, target_id) 단위로 단 하나의 북마크를 가진다.
-- user_id 는 identity-access users.id 의 UUID 를 직접 저장한다 (FK 없음 — BC 격리, V402/V404/V405 선례).
-- target_id 는 이슈 키(ATL-1 등) 등 대상 식별자를 문자열로 저장한다 (대상 BC 와 격리, 키 문자열 보관).
-- DATA.md §3 하드 삭제 예외 — 즐겨찾기 해제(unstar)는 행 즉시 제거하므로 deleted_at(소프트 삭제) 컬럼을 두지 않는다.
--   ADR 2026-06-24-fr-ux-02-favorites (FR-UX-02, Maxi 확정). Watcher 와 동일 성격(개인 토글, 복구 가치 낮음).
CREATE TABLE favorites (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL,
    target_type VARCHAR(20)  NOT NULL,
    target_id   VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_favorites_user_target UNIQUE (user_id, target_type, target_id)
);

COMMENT ON TABLE  favorites             IS '사용자별 즐겨찾기(북마크) — (사용자×대상) 단위로 단 하나. 하드 삭제(DATA.md §3)';
COMMENT ON COLUMN favorites.user_id     IS '즐겨찾기한 사용자 ID (identity-access users.id). FK 없음 — BC 격리';
COMMENT ON COLUMN favorites.target_type IS '즐겨찾기 대상 유형 (예: ISSUE)';
COMMENT ON COLUMN favorites.target_id   IS '즐겨찾기 대상 식별자 (예: 이슈 키 ATL-1). 대상 BC 와 격리해 문자열 보관';
COMMENT ON COLUMN favorites.created_at  IS '즐겨찾기 추가 시각 (TIMESTAMPTZ, DATA.md §4)';
