-- automation BC 액션 화이트리스트 확장 — automation_actions.action_type 4종 → 5종 (FR-AT-07 PR-B)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V305 사용 중 → V306.
--
-- SET_FIX_VERSIONS: 이슈의 수정 버전(fixVersions)을 설정하는 신규 액션. PR 머지 등 트리거로 릴리스 버전을
-- 자동 태깅하기 위한 액션이며, Kotlin ActionType enum 에도 같은 상수가 추가된다(앱·DB 이중 방어 유지).
--
-- V302 가 만든 CHECK 제약(4종)은 **이미 적용된 마이그레이션**이라 편집하지 않는다 — 편집 시 Flyway 체크섬
-- 드리프트가 발생한다(적용된 마이그레이션 불변). 대신 신규 V306 에서 DROP → ADD 로 화이트리스트를 교체한다.
--
-- action_type 은 VARCHAR(20) 이며 'SET_FIX_VERSIONS'(16자)가 들어가므로 컬럼 폭 변경은 필요 없다.
-- 기존 행은 모두 4종 중 하나이므로 새 CHECK(5종 상위집합)를 즉시 만족한다 — backfill 불필요, 검증 실패 없음.

-- 기존 4종 제약 제거 → 5종으로 재생성. 제약명은 V302 와 동일하게 유지한다
-- (테스트·운영 진단이 제약명으로 위반을 식별하므로 이름을 바꾸지 않는다).
ALTER TABLE automation_actions DROP CONSTRAINT ck_automation_actions_action_type;

ALTER TABLE automation_actions ADD CONSTRAINT ck_automation_actions_action_type CHECK (
    action_type IN ('SET_FIELD', 'ASSIGN', 'ADD_COMMENT', 'CALL_WEBHOOK', 'SET_FIX_VERSIONS')
);

-- COMMENT 재발행 — V302:42·45 가 "4종" 으로 박아둔 건 살아있는 DB 객체다. 여기서 갱신하지 않으면
-- 운영 DB 의 코멘트에 "4종" drift 가 영구히 남는다.
COMMENT ON TABLE  automation_actions             IS 'AutomationRule 액션 — 룰당 순서대로 실행하는 5종 액션(FR-AT-02, FR-AT-07)';
COMMENT ON COLUMN automation_actions.action_type IS '액션 종류 — CHECK 5종(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK/SET_FIX_VERSIONS)';
