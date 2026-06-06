-- issue-tracking V014 — issues.security_level_id UUID NULL 컬럼 추가 (이슈 보안 수준, FK 미적용)
--
-- security_level_id 는 identity-access BC 소유의 보안 등급(issue_security_levels.id)에 대응한다.
-- BC 격리 원칙상 FK 를 적용하지 않는다 (V007 assignee_id 동형) — 등급 존재 검증과 차단 판정은
-- ApplicationService / cross-BC 포트(IssueSecurityLookup·IssueSecurityDirectory)가 수행한다.
-- NULL = 등급 미지정 (모든 VIEW 통과자에게 공개). 기존 row 는 NULL 로 backfill 불필요 (NULL 허용 컬럼).

ALTER TABLE issues ADD COLUMN security_level_id UUID NULL;

COMMENT ON COLUMN issues.security_level_id IS 'identity-access BC issue_security_levels.id 대응 보안 등급. BC 격리로 FK 미적용 — ApplicationService/cross-BC 포트가 판정. null=미지정(공개).';
