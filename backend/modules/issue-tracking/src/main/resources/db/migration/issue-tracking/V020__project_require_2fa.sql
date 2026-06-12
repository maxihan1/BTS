-- projects.require_2fa 컬럼 추가 — FR-MF-04 MFA 강제 정책. DATA.md §4-2 NOT NULL DEFAULT 준수

-- require_2fa: '민감 프로젝트'(MFA 강제 대상) 표시. 기존 행은 DEFAULT 로 false.
-- SensitiveProjectResolver(shared-kernel 포트)가 이 컬럼을 읽어 멤버의 MFA 강제 여부를 판정한다.
ALTER TABLE projects ADD COLUMN require_2fa BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN projects.require_2fa IS '민감 프로젝트 여부 — true 면 멤버는 MFA(2FA) 강제 대상 (FR-MF-04). SYSTEM_ADMIN 만 토글.';
