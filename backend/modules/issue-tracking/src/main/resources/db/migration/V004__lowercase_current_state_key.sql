-- issue-tracking V004 — issues.current_state_key 대문자 데이터를 소문자(워크플로우 정본 키)로 정규화
UPDATE issues
  SET current_state_key = LOWER(current_state_key)
WHERE current_state_key <> LOWER(current_state_key);
