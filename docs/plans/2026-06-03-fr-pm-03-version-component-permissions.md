# FR-PM-03 — 버전/컴포넌트 등록 권한

> slug: fr-pm-03-version-component-permissions
> type: auth
> agent: security-engineer (주축) + db-engineer(D3) + frontend-engineer(D6) + qa-engineer(D7)
> primary_bc: identity-access
> 생성: 2026-06-03

## Brief

FR-PM-03 — 버전/컴포넌트 등록 권한. 두 권한 리졸버 포트(`ComponentPermissionResolver`,
`VersionPermissionResolver`)의 prod 구현 + `permission_schemes` 매트릭스를 채운다.

- 선행 충족. FR-CM-01(컴포넌트 CRUD, PR #59) + FR-VR-01(버전 CRUD, PR #67) 모두 완료.
  두 리졸버는 현재 포트로 추상화되어 prod 실판정이 FR-PM-03으로 이연된 상태.
- 관련 ADR. docs/adr/2026-06-02-component-model-and-permission-deferral.md,
  docs/adr/2026-06-03-version-model-and-permission-deferral.md
- plan 문서. docs/plan/product/identity-access.md §4.3
- classify 정정. classify-task.ts가 '버전/컴포넌트' 단어로 ui/frontend 오분류 →
  plan §4.3 근거로 auth/security-engineer 정정.

### 산출물(D1~D7, plan §4.3)
- D1. 도메인 (security-engineer)
- D2. 명세 (security-engineer)
- D3. 데이터 모델 — FR-PM-02 활용(신규 마이그레이션 여부 spec에서 확정) (db-engineer)
- D4. 백엔드 — `@PreAuthorize` 추가 / 두 리졸버 prod 구현 (security-engineer)
- D5. 백엔드 테스트 (security-engineer)
- D6. 프론트 UI (frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
