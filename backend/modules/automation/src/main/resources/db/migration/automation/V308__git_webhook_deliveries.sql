-- automation BC Git 웹훅 배달 중복수신 방어 — git_webhook_deliveries 테이블 (FR-AT-07 PR-C)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V307 사용 중 → V308.
--
-- provider 가 보낸 배달 식별자(GitHub X-GitHub-Delivery / GitLab X-Gitlab-Event-UUID)를 기록해 **같은 배달의
-- 재전송을 1회만 처리**하도록 강제한다. GitHub/GitLab 은 타임아웃·재시도 시 같은 delivery_id 로 재전송하므로,
-- 이 테이블이 없으면 PR 머지 1건이 룰을 여러 번 발화시킨다(중복 액션 실행).
--
-- ★멱등성은 (webhook_id, delivery_id) 복합 PK 로 DB 가 강제한다 — 앱의 "먼저 조회 후 없으면 INSERT" 는
--   동시 재전송에서 TOCTOU 로 뚫린다. PK 는 두 컬럼 모두 NOT NULL 을 함의하므로 NULL 다중삽입 우회도 없다
--   (PG UNIQUE 의 NULLS DISTINCT 함정이 PK 에는 성립하지 않음). 소비자는 INSERT 성공 여부로 신규 배달을 판정한다.
--
-- ★append-only 수신 로그 — 소프트 삭제(deleted_at) 대상이 아니다. 배달 수신은 사실 기록이라 수정하지 않는다.
--   DATA.md §3 소프트삭제 기본의 명시적 예외(webhook_deliveries V603:8-9 선례 동형).
--   무한 증식을 막는 보존 정책(received_at 기준 정리 배치)은 T18 이 담당한다.
--
-- webhook_id: git_webhooks FK. 웹훅 삭제 시 그 배달 이력도 함께 사라져야 고아 행이 남지 않으므로 ON DELETE CASCADE
--   (조인/종속 테이블 FK CASCADE 관례 — Testcontainers cleanup 도 이에 의존).
-- delivery_id: provider 가 발급한 배달 UUID/식별자. provider 별 형식이 달라 폭 128 로 여유를 둔다.

CREATE TABLE git_webhook_deliveries (
    webhook_id  UUID NOT NULL REFERENCES git_webhooks(id) ON DELETE CASCADE,  -- 소속 웹훅(삭제 시 이력 동반 삭제)
    delivery_id VARCHAR(128) NOT NULL,                                        -- provider 배달 식별자(재전송 시 동일값)
    received_at TIMESTAMPTZ NOT NULL,                                         -- 수신 시각(앱 Clock 공급) — T18 정리 기준
    -- 복합 PK = 중복수신 방어의 마지막 방어선. 재전송은 여기서 유니크 위반으로 거부된다.
    PRIMARY KEY (webhook_id, delivery_id)
);

-- ── 인덱스 ────────────────────────────────────────────────────────────────────
-- 방금 생성한 빈 테이블이므로 CONCURRENTLY 를 쓰지 않는다 — CREATE INDEX CONCURRENTLY 는 Flyway 트랜잭션
-- 안에서 실행 불가하며, 0행 신규 테이블에는 락 회피 이점도 없다(V300:42-43 동형).

-- FK(webhook_id) 조회 인덱스는 별도로 만들지 않는다 — 복합 PK (webhook_id, delivery_id) 의 **선두 컬럼**이
-- webhook_id 라 PK 인덱스가 그대로 FK 조회/삭제 CASCADE 경로를 커버한다. DATA.md §4.1#6(FK 명시 인덱스)의
-- 취지는 충족되며, 중복 인덱스를 추가하면 쓰기 비용만 늘어난다.

-- 보존 정책 정리 배치(T18) — received_at < 기준시각 범위 삭제 스캔용.
CREATE INDEX ix_git_webhook_deliveries_received_at
    ON git_webhook_deliveries (received_at);

COMMENT ON TABLE  git_webhook_deliveries             IS 'Git 웹훅 배달 수신 이력 — 복합 PK 로 재전송 멱등성 강제(FR-AT-07 PR-C), append-only';
COMMENT ON COLUMN git_webhook_deliveries.webhook_id  IS '소속 git_webhooks id — 웹훅 삭제 시 ON DELETE CASCADE';
COMMENT ON COLUMN git_webhook_deliveries.delivery_id IS 'provider 배달 식별자(X-GitHub-Delivery / X-Gitlab-Event-UUID) — 재전송 시 동일값';
COMMENT ON COLUMN git_webhook_deliveries.received_at IS '수신 시각 — 보존 정책 정리 배치(T18) 기준 컬럼';
