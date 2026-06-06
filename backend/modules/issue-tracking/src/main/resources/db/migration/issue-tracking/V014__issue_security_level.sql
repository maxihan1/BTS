-- issue-tracking V014 — issues.security_level_id UUID NULL 컬럼 추가 (이슈 보안 수준, FK 미적용)
--
-- security_level_id 는 identity-access BC 소유의 보안 등급(issue_security_levels.id)에 대응한다.
-- FK 를 일부러 적용하지 않는 이유. 등급 테이블이 다른 BC(identity-access) 소유라
-- issue-tracking 이 그 스키마에 물리적으로 결합되면 BC 격리가 깨지기 때문이다 (V007 assignee_id 동형).
-- 따라서 등급 존재 검증과 이슈 차단 판정은 ApplicationService 와 cross-BC 포트
-- (IssueSecurityLookup·IssueSecurityDirectory, ADR 2026-06-06)가 대신 수행한다.
-- NULL = 등급 미지정 (모든 VIEW 통과자에게 공개). NULL 허용 컬럼이라 기존 row backfill 불필요.

ALTER TABLE issues ADD COLUMN security_level_id UUID NULL;

COMMENT ON COLUMN issues.security_level_id IS 'identity-access BC issue_security_levels.id 대응 보안 등급. BC 격리로 FK 미적용 — ApplicationService/cross-BC 포트가 판정. null=미지정(공개).';
