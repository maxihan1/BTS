-- slack-integration BC Slack App 설치 + 봇 토큰 보관 — slack_installs 테이블 (FR-SL-01)
-- ⚠ V번호는 slack-integration BC 예약 범위 V700~V799 (DATA.md §4.1 BC 별 100단위). 첫 마이그레이션 = V700.
--
-- SlackInstall(워크스페이스별 봇 설치) 영속 테이블. OAuth v2 설치 콜백에서 oauth.v2.access 토큰 교환 후
-- bot token 을 AES 암호화(SecretEncryptor)해 저장한다. 워크스페이스(team_id) 단위 재설치는 upsert 로 멱등
-- 처리한다(ON CONFLICT (team_id) DO UPDATE — Task 7).
--
-- 소프트 삭제(deleted_at) 없음 — 설치 상태는 team_id 단위 upsert 로 최신 1행만 유지하며, 연결 해제(revoke)는
-- 행 즉시 제거로 처리한다(임시 자격증명 성격, DATA.md §3 하드 삭제 선례 dashboard_share_tokens 동형).
-- OCC(낙관적 잠금)도 범위 아님 — 마지막 콜백이 승자(last-write-wins), 동일 워크스페이스 동시 설치는 실무상 부재.
--
-- enterprise install(조직 전체 설치)은 애플리케이션 레벨에서 거부한다(spec — team null 이면 unsupported).
-- is_enterprise_install 컬럼은 방어적 기록용이며 DEFAULT false.

CREATE TABLE slack_installs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id               TEXT NOT NULL,                       -- Slack workspace id — upsert 기준
    team_name             TEXT NOT NULL,                       -- 워크스페이스 표시명
    bot_user_id           TEXT NOT NULL,                       -- 봇 사용자 id (Uxxxx)
    app_id                TEXT NOT NULL,                       -- Slack App id (Axxxx)
    bot_token_encrypted   TEXT NOT NULL,                       -- 봇 토큰 AES 암호문(hex) — 평문 저장/로깅 금지
    scopes                TEXT NOT NULL,                       -- 발급 스코프 CSV
    is_enterprise_install BOOLEAN NOT NULL DEFAULT false,      -- 조직 전체 설치 여부(앱 레벨 거부, 방어적 기록)
    installed_by          UUID NOT NULL,                       -- BTS user id (cross-BC, BC 격리로 FK 아님)
    installed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 최초 설치 시각
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),  -- upsert 갱신 시각
    -- team_id 단위 멱등 upsert(ON CONFLICT (team_id))의 대상 제약. 워크스페이스당 설치 1행 보장.
    CONSTRAINT uq_slack_installs_team_id UNIQUE (team_id)
);
