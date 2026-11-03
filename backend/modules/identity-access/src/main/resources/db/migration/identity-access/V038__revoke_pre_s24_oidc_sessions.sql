-- S24 배포 때 S24 전 OIDC 세션(auth_sid 없음)을 한 번 끊는다 — back-channel 로 끊을 수 없는 꼬리(최대 14일)를 없앤다 (FR-AU-04 · FR-AU-09)
-- 2026-11-03 Maxi 결정. 같은 배포에서 Auth 세션도 모두 사라져 어차피 한 번 다시 로그인한다.
-- 소프트 갱신만 — 행을 지우지 않는다 (DATA.md §1 원칙 2). 세션 폐기와 refresh chain 폐기는 짝 (AuthController.logout 과 같음).
-- 기동 때 V037 바로 뒤에 돌아 새 칸이 모두 NULL 이므로 「S24 전 oidc 세션 전부」와 같다. 로컬 · SAML 세션은 그대로.

UPDATE refresh_tokens
   SET used_at = NOW()
 WHERE used_at IS NULL
   AND session_id IN (SELECT id
                        FROM sessions
                       WHERE provider_id = 'oidc'
                         AND auth_sid IS NULL
                         AND revoked_at IS NULL);

UPDATE sessions
   SET revoked_at    = NOW(),
       revoke_reason = 's24_relogin'
 WHERE provider_id = 'oidc'
   AND auth_sid IS NULL
   AND revoked_at IS NULL;
