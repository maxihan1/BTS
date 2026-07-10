-- automation BC 자동화 룰(트리거 전용) — automation_rules 테이블 (FR-AT-01, ADR D1)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). 첫 마이그레이션 = V300.
--
-- AutomationRule(TCA 자동화 엔진의 트리거 절반) 영속 테이블. FR-AT-01 범위에서는 **트리거만** 저장한다 —
-- 조건(FR-AT-03)·액션(FR-AT-02) 컬럼은 후행 FR 이 add 마이그레이션으로 추가한다(ADR D1).
--
-- trigger_type(5종)은 DB CHECK 제약으로 화이트리스트를 강제한다 — 데이터 무결성은 시스템의 마지막 방어선이므로
-- 앱 검증(TriggerType enum, Task 3)에 더해 DB 레벨에서도 이중 방어한다.
-- trigger_config(JSONB)는 트리거별 형식만 담는다(favorites/가젯 선례 — cross-BC 존재 검증 안 함). 예:
--   ISSUE_UPDATED = {fields: [...]} 필터 / SCHEDULED = {cron: "0 0 9 * * *"}(Spring CronExpression 6필드) / WEBHOOK = 발급 토큰 부수정보.
--
-- webhook_token_hash: WEBHOOK 트리거 전용(그 외 NULL). 인바운드 토큰의 SHA-256 해시만 저장한다(평문 미저장).
-- next_fire_at: SCHEDULED 트리거 전용(그 외 NULL). @Scheduled 워커의 발화 대상 조회 + 중복억제 기준(Task 8).
-- created_by: BTS user id(cross-BC, BC 격리로 FK 아님 — slack_installs.installed_by 동형).
-- version: OCC(낙관적 잠금) 카운터. 룰 갱신 시 도메인이 version 을 bump 한다.
-- deleted_at: 소프트 삭제(DATA.md §3 — 새 엔티티 테이블 기본 컬럼). 조회는 WHERE deleted_at IS NULL 로 제외.

-- gen_random_uuid() 보장 — pgcrypto 확장(PostgreSQL 13+ 는 pgcrypto 없이도 gen_random_uuid 제공하나 방어적 선언).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE automation_rules (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key        VARCHAR(50) NOT NULL,                  -- 룰이 속한 프로젝트 키(cross-BC, FK 아님)
    name               VARCHAR(255) NOT NULL,                 -- 룰 표시명
    enabled            BOOLEAN NOT NULL DEFAULT true,         -- 활성 여부 — 비활성 룰은 발화하지 않음
    trigger_type       VARCHAR(20) NOT NULL,                  -- 트리거 종류(CHECK 5종 화이트리스트)
    trigger_config     JSONB NOT NULL DEFAULT '{}',           -- 트리거별 형식 설정(cron/fields/토큰 등)
    webhook_token_hash VARCHAR(64),                           -- WEBHOOK 전용 — 인바운드 토큰 SHA-256 해시(평문 미저장)
    next_fire_at       TIMESTAMPTZ,                           -- SCHEDULED 전용 — 다음 발화 예정 시각(UTC)
    created_by         UUID NOT NULL,                         -- 생성자 BTS user id(cross-BC, FK 아님)
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),    -- 생성 시각
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),    -- 갱신 시각
    version            BIGINT NOT NULL DEFAULT 0,             -- OCC 낙관적 잠금 카운터
    deleted_at         TIMESTAMPTZ,                           -- 소프트 삭제 시각(NULL = 활성)
    -- trigger_type 5종 화이트리스트(ADR D1). 앱(TriggerType enum, Task 3)과 이중 방어.
    CONSTRAINT ck_automation_rules_trigger_type CHECK (
        trigger_type IN ('ISSUE_CREATED', 'ISSUE_UPDATED', 'ISSUE_COMMENTED', 'SCHEDULED', 'WEBHOOK')
    )
);

-- ── 인덱스 ────────────────────────────────────────────────────────────────────
-- 방금 생성한 빈 테이블이므로 CONCURRENTLY 를 쓰지 않는다 — CREATE INDEX CONCURRENTLY 는 Flyway 트랜잭션
-- 안에서 실행 불가하며, 0행 신규 테이블에는 락 회피 이점도 없다(DATA.md CONCURRENTLY 는 대형 기존 테이블 대상).

-- (1) 이벤트 트리거 매칭 조회 — AutomationEventWorker.findEnabledByProjectAndTriggerType (Task 4/7).
--     활성 룰만 조회하므로 deleted_at IS NULL 부분 인덱스로 소프트 삭제 행을 인덱스에서 배제한다.
CREATE INDEX idx_automation_rules_project_trigger_enabled
    ON automation_rules (project_key, trigger_type, enabled)
    WHERE deleted_at IS NULL;

-- (2) 웹훅 토큰 조회 + 유일성 — AutomationWebhookController 토큰 해시 lookup (Task 9).
--     WEBHOOK 전용 컬럼이라 부분 UNIQUE 인덱스로 (a) 토큰 해시 충돌 거부 (b) NULL 다수(비웹훅 룰) 미인덱싱.
CREATE UNIQUE INDEX uq_automation_rules_webhook_token_hash
    ON automation_rules (webhook_token_hash)
    WHERE webhook_token_hash IS NOT NULL;

-- (3) 스케줄 발화 대상 조회 — AutomationScheduleWorker.findScheduledDue(now) (Task 8).
--     SCHEDULED 전용 컬럼이라 부분 인덱스로 발화 대상만 인덱싱한다.
CREATE INDEX idx_automation_rules_next_fire_at
    ON automation_rules (next_fire_at)
    WHERE trigger_type = 'SCHEDULED';

COMMENT ON TABLE  automation_rules                    IS 'AutomationRule Aggregate — 트리거 전용 스키마(FR-AT-01, 조건/액션은 FR-AT-02/03)';
COMMENT ON COLUMN automation_rules.project_key        IS '룰이 속한 프로젝트 키(cross-BC, BC 격리로 FK 아님)';
COMMENT ON COLUMN automation_rules.name               IS '룰 표시명';
COMMENT ON COLUMN automation_rules.enabled            IS '활성 여부 — 비활성 룰은 발화하지 않음(DEFAULT true)';
COMMENT ON COLUMN automation_rules.trigger_type       IS '트리거 종류 — CHECK 5종(ISSUE_CREATED/ISSUE_UPDATED/ISSUE_COMMENTED/SCHEDULED/WEBHOOK)';
COMMENT ON COLUMN automation_rules.trigger_config     IS '트리거별 형식 설정 JSONB(cron/fields/토큰 부수정보)';
COMMENT ON COLUMN automation_rules.webhook_token_hash IS 'WEBHOOK 전용 — 인바운드 토큰 SHA-256 해시(평문 미저장), 그 외 NULL';
COMMENT ON COLUMN automation_rules.next_fire_at       IS 'SCHEDULED 전용 — 다음 발화 예정 시각(UTC), 그 외 NULL';
COMMENT ON COLUMN automation_rules.created_by         IS '생성자 BTS user id(cross-BC, BC 격리로 FK 아님)';
COMMENT ON COLUMN automation_rules.version            IS 'OCC 낙관적 잠금 카운터 — 갱신 시 도메인이 bump';
COMMENT ON COLUMN automation_rules.deleted_at         IS '소프트 삭제 시각(NULL = 활성) — 조회는 WHERE deleted_at IS NULL';
