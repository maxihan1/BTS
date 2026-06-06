-- projects.lead_user_id 컬럼 추가 — FR-CM-04 프로젝트 리드 폴백(components.lead_user_id 동형)

-- lead_user_id: identity-access BC users.id 대응. BC 격리 원칙으로 FK 미적용 — ApplicationService 가 존재 guard.
ALTER TABLE projects ADD COLUMN lead_user_id UUID NULL;

COMMENT ON COLUMN projects.lead_user_id IS 'identity-access BC users.id 대응 프로젝트 리드. BC 격리로 FK 미적용 — ApplicationService 가 존재 guard.';
