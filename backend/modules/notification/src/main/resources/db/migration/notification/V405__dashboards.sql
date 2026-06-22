-- 사용자 정의 대시보드 + 공유 테이블 (FR-DB-01 V405)

-- ── dashboards ────────────────────────────────────────────────────────────────
-- 사용자 정의 대시보드 Aggregate. layout(JSONB)에 위젯 배치를 저장하고 visibility 로 공개 범위를 제어한다.
-- owner_id 는 identity-access users.id 의 UUID 를 직접 저장한다 (FK 없음 — BC 격리, V402/V404 선례).
-- DATA.md §3: 새 엔티티 테이블이므로 deleted_at(소프트 삭제) 컬럼을 둔다.
-- version 은 낙관적 동시성 제어(OCC)용 — 동시 편집 충돌을 감지한다.
CREATE TABLE dashboards (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    UUID        NOT NULL,                 -- identity-access users.id (논리 참조, FK 없음)
    name        TEXT        NOT NULL,
    description TEXT,
    visibility  TEXT        NOT NULL,                 -- PRIVATE | TEAM | ORG
    layout      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,                          -- 소프트 삭제 (DATA.md §3)
    version     BIGINT      NOT NULL DEFAULT 0
);

-- 소유자별 대시보드 목록 조회 경로 최적화 — 삭제되지 않은 행만 인덱싱(부분 인덱스).
CREATE INDEX idx_dashboards_owner ON dashboards(owner_id) WHERE deleted_at IS NULL;
-- 공개 범위별 조회(TEAM/ORG 공유 대시보드 탐색) 경로 최적화 — 삭제되지 않은 행만 인덱싱.
CREATE INDEX idx_dashboards_visibility ON dashboards(visibility) WHERE deleted_at IS NULL;

-- ── dashboard_shares ──────────────────────────────────────────────────────────
-- 대시보드 개별 공유(특정 사용자 지정 공유) 조인 테이블. (dashboard_id, user_id) 단위로 단 하나.
-- 부모 대시보드 삭제 시 공유 행은 함께 제거되어야 하므로 FK 는 ON DELETE CASCADE (조인 테이블 고아 행 방지).
-- user_id 는 identity-access users.id 의 UUID 를 직접 저장한다 (FK 없음 — BC 격리).
CREATE TABLE dashboard_shares (
    dashboard_id UUID NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL,
    PRIMARY KEY (dashboard_id, user_id)
);

-- 사용자에게 공유된 대시보드 역방향 조회 경로 최적화 (PK 선두 컬럼이 dashboard_id 라 user_id 단독 조회용 별도 인덱스 필요).
CREATE INDEX idx_dashboard_shares_user ON dashboard_shares(user_id);
