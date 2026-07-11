-- automation BC 룰 실행 주체 컬럼 추가 — automation_rules.actor_user_id (FR-AT-02, ADR D1)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V302 사용 중 → V303.
--
-- actor_user_id: 액션 executor 가 액션(ASSIGN/ADD_COMMENT 등)을 **누구 권한으로** 실행할지 결정하는 실행 주체
-- (BTS user id, cross-BC 이므로 BC 격리로 FK 아님 — created_by 동형). 감사 로그·권한 판정의 행위자 기준이 된다.
--
-- NOT NULL 컬럼 추가는 backfill 동반 필수(DATA.md §4.1#2). 3단계로 안전하게 추가한다:
--   (1) nullable 로 컬럼 추가 → 기존 행은 NULL
--   (2) 기존 행 backfill — 룰 생성자(created_by, V300 에서 NOT NULL)를 실행 주체 기본값으로 채운다
--   (3) SET NOT NULL — 이후 모든 행은 actor_user_id 를 반드시 갖는다
-- 이 UPDATE 는 스키마 추가에 필수적인 backfill(데이터 이관)이며 비즈니스 로직이 아니다.

-- (1) nullable 로 추가
ALTER TABLE automation_rules ADD COLUMN actor_user_id UUID;

-- (2) 기존 행 backfill — created_by(NOT NULL) 를 실행 주체 기본값으로
UPDATE automation_rules SET actor_user_id = created_by WHERE actor_user_id IS NULL;

-- (3) NOT NULL 강제
ALTER TABLE automation_rules ALTER COLUMN actor_user_id SET NOT NULL;

COMMENT ON COLUMN automation_rules.actor_user_id IS '룰 실행 주체 BTS user id(액션을 이 사용자 권한으로 실행, cross-BC FK 아님)';
