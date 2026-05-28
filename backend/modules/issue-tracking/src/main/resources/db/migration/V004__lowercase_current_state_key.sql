-- issue-tracking V004 — issues.current_state_key 대문자 데이터를 소문자(워크플로우 정본 키)로 정규화
-- WHERE 조건: 이미 소문자인 row 는 건드리지 않음 → 멱등성 보장.
-- updated_at 갱신 제외: 상태 전이가 아닌 데이터 정합성 교정이므로 audit 타임스탬프 변경 불필요.
UPDATE issues
  SET current_state_key = LOWER(current_state_key)
WHERE current_state_key <> LOWER(current_state_key);
