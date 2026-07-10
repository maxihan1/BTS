-- user_slack_mapping 역방향 조회(slack_user_id → user_id)용 UNIQUE 인덱스 (FR-SL-03 Slack Unfurl)

CREATE UNIQUE INDEX idx_user_slack_mapping_slack_user
    ON user_slack_mapping (slack_user_id, team_id);
