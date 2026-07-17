-- automation BC Git 인바운드 웹훅 등록 — git_webhooks 테이블 (FR-AT-07 PR-C)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V306 사용 중 → V307.
--
-- GitHub/GitLab 이 PR 머지 이벤트를 통지할 인바운드 엔드포인트의 등록 정보. 프로젝트별로 웹훅을 발급하면
-- 수신 URL 에 담긴 토큰으로 웹훅을 식별(token_hash)하고, provider 서명을 secret 으로 검증한다.
--
-- provider(2종)는 DB CHECK 제약으로 화이트리스트를 강제한다 — 데이터 무결성은 시스템의 마지막 방어선이므로
-- 앱 검증(GitProvider enum)에 더해 DB 레벨에서도 이중 방어한다(V300 trigger_type 선례 동형).
--
-- token_hash: 인바운드 URL 토큰의 SHA-256 해시(64자 hex)만 저장한다. 평문 토큰은 발급 시 1회 노출 후 미저장
--   (automation_rules.webhook_token_hash 선례 동형) — 유출 시 DB 만으로는 유효 토큰을 복원할 수 없다.
-- secret_encrypted: provider 서명(HMAC) 검증용 공유 secret 의 AES-256-GCM 암호문. 원문 비저장
--   (DEVELOPMENT §1.1.2, outbound_webhooks.secret_encrypted V603:6 선례). 단 여기서는 서명 검증에
--   반드시 필요하므로 NOT NULL — secret 없는 웹훅은 서명을 검증할 수 없어 등록 자체를 허용하지 않는다(fail-closed).
-- project_key: cross-BC 참조라 FK 아님(BC 격리). 폭 10 은 정본 issue-tracking projects.key
--   VARCHAR(10) CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$') 와 정확히 일치시킨 값이다 — 유효 키는 최대 10자라
--   절단 위험이 없다. 같은 BC 의 automation_rules.project_key(VARCHAR(50))보다 좁지만, 정본 제약을 그대로
--   반영하는 쪽이 방어적으로 더 타이트하다.
-- created_by: BTS user id(cross-BC, BC 격리로 FK 아님 — automation_rules.created_by 동형).
-- deleted_at: 소프트 삭제(DATA.md §3 — 새 엔티티 테이블 기본 컬럼). 조회는 WHERE deleted_at IS NULL 로 제외.
--
-- id/created_at 에 DB DEFAULT 를 두지 않는다 — 앱이 UUID 와 Clock 주입 시각을 공급한다(테스트에서 시각 고정 가능).

CREATE TABLE git_webhooks (
    id               UUID PRIMARY KEY,            -- 앱 공급 UUID(테스트 결정성 — DB DEFAULT 미사용)
    project_key      VARCHAR(10) NOT NULL,        -- 웹훅이 속한 프로젝트 키(cross-BC, FK 아님 / 정본 projects.key 폭)
    provider         VARCHAR(16) NOT NULL,        -- Git 호스팅 제공자(CHECK 2종 화이트리스트)
    token_hash       VARCHAR(64) NOT NULL,        -- 인바운드 URL 토큰 SHA-256 해시 hex(평문 미저장)
    secret_encrypted TEXT NOT NULL,               -- 서명 검증용 secret 의 AES-256-GCM 암호문(원문 비저장)
    created_at       TIMESTAMPTZ NOT NULL,        -- 생성 시각(앱 Clock 공급)
    created_by       UUID NOT NULL,               -- 생성자 BTS user id(cross-BC, FK 아님)
    deleted_at       TIMESTAMPTZ,                 -- 소프트 삭제 시각(NULL = 활성)
    -- provider 2종 화이트리스트. 앱(GitProvider enum)과 이중 방어.
    CONSTRAINT ck_git_webhooks_provider CHECK (
        provider IN ('GITHUB', 'GITLAB')
    )
);

-- ── 인덱스 ────────────────────────────────────────────────────────────────────
-- 방금 생성한 빈 테이블이므로 CONCURRENTLY 를 쓰지 않는다 — CREATE INDEX CONCURRENTLY 는 Flyway 트랜잭션
-- 안에서 실행 불가하며, 0행 신규 테이블에는 락 회피 이점도 없다(V300:42-43 동형).

-- 인바운드 웹훅 수신 시 토큰 해시 lookup + 유일성. 활성 웹훅만 대상이므로 deleted_at IS NULL 부분 UNIQUE 로
-- (a) 활성 토큰 해시 충돌 거부 (b) 소프트 삭제된 과거 웹훅의 해시를 유일성 판정에서 배제한다 —
-- 삭제된 웹훅이 토큰을 영구 점유하지 않는다. 조회 경로도 같은 술어(deleted_at IS NULL)라 인덱스가 그대로 쓰인다.
-- (V300:53-55 uq_automation_rules_webhook_token_hash 부분 UNIQUE 선례 동형, 술어만 소프트삭제 기준)
CREATE UNIQUE INDEX uq_git_webhooks_token_hash
    ON git_webhooks (token_hash)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE  git_webhooks                  IS 'Git 인바운드 웹훅 등록 — PR 머지 통지 수신 엔드포인트(FR-AT-07 PR-C)';
COMMENT ON COLUMN git_webhooks.project_key      IS '웹훅이 속한 프로젝트 키(cross-BC, BC 격리로 FK 아님)';
COMMENT ON COLUMN git_webhooks.provider         IS 'Git 호스팅 제공자 — CHECK 2종(GITHUB/GITLAB)';
COMMENT ON COLUMN git_webhooks.token_hash       IS '인바운드 URL 토큰 SHA-256 해시(평문 미저장) — 발급 시 1회 노출';
COMMENT ON COLUMN git_webhooks.secret_encrypted IS '서명 검증용 secret 의 AES-256-GCM 암호문(원문 비저장)';
COMMENT ON COLUMN git_webhooks.created_by       IS '생성자 BTS user id(cross-BC, BC 격리로 FK 아님)';
COMMENT ON COLUMN git_webhooks.deleted_at       IS '소프트 삭제 시각(NULL = 활성) — 조회는 WHERE deleted_at IS NULL';
