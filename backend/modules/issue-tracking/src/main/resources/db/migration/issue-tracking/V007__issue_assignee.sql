-- issue-tracking V007 — issues.assignee_id UUID NULL 컬럼 추가 (담당자, FK 미적용)
--
-- assignee_id 는 identity-access BC 의 users.id 에 대응하지만 BC 격리 원칙상 FK 를 적용하지 않는다.
-- 존재 여부 검증은 ApplicationService 가 UserLookupPort 를 통해 수행한다.
-- NULL = 미할당 (담당자 없음). 기존 row 는 NULL 로 backfill 불필요 (NULL 허용 컬럼).

ALTER TABLE issues ADD COLUMN assignee_id UUID NULL;

COMMENT ON COLUMN issues.assignee_id IS 'identity-access BC users.id 대응. BC 격리로 FK 미적용 — ApplicationService 가 UserLookupPort 로 존재 guard. null=미할당.';
