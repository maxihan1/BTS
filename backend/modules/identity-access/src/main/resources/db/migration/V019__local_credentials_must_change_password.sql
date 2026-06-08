-- local_credentials 에 강제 비밀번호 변경 플래그 추가 (FR-AU-05 관리자 회원가입 — 초기 임시 비밀번호 강제 교체)

-- must_change_password: true 이면 사용자는 다음 로그인 시 비밀번호를 반드시 변경해야 한다.
-- 관리자가 신규 계정을 생성하며 임시 비밀번호를 발급한 경우 true 로 저장된다.
-- 정상적인 비밀번호 변경(rotate) 성공 시 UPSERT 의 ON CONFLICT DO UPDATE SET 경로로 false 로 자동 해제된다.
-- NOT NULL DEFAULT FALSE — 기존 행(외부 IdP 비대상 로컬 계정)은 강제 변경 대상이 아니므로 false.
ALTER TABLE local_credentials
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN local_credentials.must_change_password
    IS '강제 비밀번호 변경 플래그 — true 면 다음 로그인 시 변경 필수. rotate 성공 시 UPSERT SET 으로 false 자동 해제 (FR-AU-05)';
