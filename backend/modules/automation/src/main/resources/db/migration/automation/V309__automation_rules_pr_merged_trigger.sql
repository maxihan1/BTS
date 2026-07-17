-- automation BC 트리거 화이트리스트 확장 — automation_rules.trigger_type 5종 → 6종 (FR-AT-07 PR-C)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V308 사용 중 → V309.
--
-- PR_MERGED: Git PR/MR 이 머지될 때 발화하는 신규 트리거. 인바운드 Git 웹훅(V307 git_webhooks)이 수신한
-- 머지 이벤트가 이 트리거의 룰을 발화시킨다. Kotlin TriggerType enum 에도 같은 상수가 추가된다(앱·DB 이중 방어 유지).
--
-- V300 이 만든 CHECK 제약(5종)은 **이미 적용된 마이그레이션**이라 편집하지 않는다 — 편집 시 Flyway 체크섬
-- 드리프트가 발생한다(적용된 마이그레이션 불변). 대신 신규 V309 에서 DROP → ADD 로 화이트리스트를 교체한다.
-- (V306 이 action_type 4종 → 5종에서 쓴 것과 동일한 절차)
--
-- trigger_type 은 VARCHAR(20) 이며 'PR_MERGED'(9자)가 들어가므로 컬럼 폭 변경은 필요 없다.
-- 기존 행은 모두 5종 중 하나이므로 새 CHECK(6종 상위집합)를 즉시 만족한다 — backfill 불필요, 검증 실패 없음.

-- 기존 5종 제약 제거 → 6종으로 재생성. 제약명은 V300 과 동일하게 유지한다
-- (테스트·운영 진단이 제약명으로 위반을 식별하므로 이름을 바꾸지 않는다).
ALTER TABLE automation_rules DROP CONSTRAINT ck_automation_rules_trigger_type;

ALTER TABLE automation_rules ADD CONSTRAINT ck_automation_rules_trigger_type CHECK (
    trigger_type IN ('ISSUE_CREATED', 'ISSUE_UPDATED', 'ISSUE_COMMENTED', 'SCHEDULED', 'WEBHOOK', 'PR_MERGED')
);

-- COMMENT 재발행 — V300:67 이 "5종" 으로 박아둔 건 살아있는 DB 객체다. 여기서 갱신하지 않으면
-- 운영 DB 의 코멘트에 "5종" drift 가 영구히 남는다(V306:21-22 동형).
-- ※ V300:63 의 TABLE 코멘트에는 트리거 개수 표기가 없어 재발행 대상이 아니다.
COMMENT ON COLUMN automation_rules.trigger_type IS '트리거 종류 — CHECK 6종(ISSUE_CREATED/ISSUE_UPDATED/ISSUE_COMMENTED/SCHEDULED/WEBHOOK/PR_MERGED)';
