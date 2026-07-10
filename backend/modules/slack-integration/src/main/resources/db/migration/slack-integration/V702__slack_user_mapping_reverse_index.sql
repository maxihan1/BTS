-- user_slack_mapping 역방향 조회(slack_user_id → user_id)용 UNIQUE 인덱스 (FR-SL-03 Slack Unfurl)
-- ⚠ V번호는 slack-integration BC 예약 범위 V700~V799 (DATA.md §4.1 BC 별 100단위). V701 다음 = V702.
--
-- 배경: V701 은 "조회는 항상 PK(user_id) 경유이므로 slack_user_id 별도 인덱스 불요" 라고 가정했으나,
-- FR-SL-03 Slack Unfurl 은 정반대 방향을 요구한다. Slack 채널에 붙은 Atlas 이슈 URL 을 카드로 펼칠 때,
-- 공유자(Slack user)의 slack_user_id 로 열람 주체(Atlas user_id = viewer)를 역방향으로 해석해 열람 권한을 판정한다.
-- 따라서 (slack_user_id, team_id) 에 조회 인덱스가 필요하다.
--
-- 왜 평범한 INDEX 가 아니라 UNIQUE 인가:
--   한 slack_user_id(+team_id) 에 두 Atlas 계정이 매핑되면, 역방향 해석이 어느 계정을 고르느냐에 따라
--   더 높은 권한의 viewer 가 잘못 선택돼 볼 수 없어야 할 이슈 카드가 채널에 과다노출되는 fail-open 이 발생한다.
--   UNIQUE 로 이중 매핑을 스키마 차원에서 원천 차단한다(crossbc-resolver-nullable-fail-open).
--   부수 효과: FR-SL-02 연결 흐름(SlackUserMappingService.link 의 ON CONFLICT (user_id) upsert)에서
--   서로 다른 두 user 가 같은 Slack 계정을 연결하려 하면 이제 UNIQUE 위반으로 막힌다 — 의도된 fail-closed 강화다.
--
-- CONCURRENTLY 미사용: Flyway 는 마이그레이션을 트랜잭션으로 감싸고 CREATE INDEX CONCURRENTLY 는
--   트랜잭션 안에서 불가하다. user_slack_mapping 은 소규모 조회 테이블이라 일반 인덱스로 충분하다
--   (DATA.md §4 CONCURRENTLY 규칙은 대형 테이블 락 회피 대상).

CREATE UNIQUE INDEX idx_user_slack_mapping_slack_user
    ON user_slack_mapping (slack_user_id, team_id);

COMMENT ON INDEX idx_user_slack_mapping_slack_user
    IS 'slack_user_id → user_id 역방향 조회(FR-SL-03 unfurl viewer 해석) + 한 Slack 계정 이중 매핑 차단(UNIQUE, fail-closed)';
