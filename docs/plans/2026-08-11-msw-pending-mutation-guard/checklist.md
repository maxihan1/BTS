# checklist — pending mutation 누수 봉합 + 전역 가드 승격 (R2)

TODOS 「apps/web(테스트 인프라) — 지연 MSW 핸들러 34개 파일의 pending mutation 누수 미측정」 해소.

## 0. 측정 (완료)

- [x] 계측 프로브 작성 (`MutationCache.prototype.build` 래핑)
- [x] 양성 대조군 확인 — `FavoriteButton.test.tsx` pending 3건 검출
- [x] 음성 대조군 확인 — `AutomationYamlImportDialog.test.tsx` mutation 18개 · pending 0건
- [x] 전량 1회차 — 577/577 통과 · 누수 10파일 13건
- [x] 전량 2회차 — 파일::테스트 단위 **차이 0건** (flaky 아님)
- [x] 34-set·22-set 기준선 재현 (34 / 22 / 밖 20 — TODOS 수치 전건 일치)

## 1. RED — 가드를 상시 켜서 13건이 실제로 red 가 되는지 확인

- [ ] `src/test/pending-mutation-guard.ts` 작성 (추적 + 수집 + 정착 대기 헬퍼)
- [ ] `src/test/setup.ts` 에 전역 `afterEach` 단언 배선
- [ ] 전량 실행 → **13건 red** 확인 (10파일). 그 외 파일은 green 유지

## 2. GREEN — 누수 13건 봉합

관용구별 처방이 다르다. 무한 Promise 는 풀 방법이 없어 **수동 게이트로 교체**한다.

- [ ] `components/favorite/FavoriteButton.test.tsx` (3건 · 무한 Promise)
- [ ] `components/settings/ProfileForm.test.tsx` (2건 · setTimeout)
- [ ] `api/useUpdateIssueSummary.test.ts` (1건 · setTimeout)
- [ ] `components/auth/ChangePasswordForm.test.tsx` (1건 · setTimeout)
- [ ] `components/automation/GitWebhookSection.test.tsx` (1건 · setTimeout)
- [ ] `components/automation/RuleExecutionTraceRow.test.tsx` (1건 · **수동 게이트**)
- [ ] `components/issue/__tests__/CreateIssueDialog.test.tsx` (1건 · **수동 게이트**)
- [ ] `components/issues/CloneIssueDialog.test.tsx` (1건 · setTimeout)
- [ ] `components/settings/PreferencesForm.test.tsx` (1건 · setTimeout)
- [ ] `routes/issues.$key.test.tsx` (1건 · 무한 Promise + setTimeout 혼재)

## 3. 비-공허 짝 — 가드가 장식이 아님을 증명

- [ ] `src/test/pending-mutation-guard.test.ts` 계약 테스트
  - [ ] 양성 — pending 을 만들면 `collectPendingMutations()` 가 잡는다
  - [ ] 음성 — 정착시키면 빈 배열
  - [ ] 추적 설치 확인 — `new QueryClient()` 의 mutation 이 레지스트리에 등록된다
  - [ ] **배선 확인** — `setup.ts` 가 가드를 실제로 켰다 (런타임 플래그)
- [ ] 계약 테스트 자신이 전역 가드에 걸리지 않는다 (테스트 안에서 정리)

## 4. 문서 동기화

- [ ] `TODOS.md` 해당 항목 해소 처리 + 실측값으로 정정 (34 → 10파일 13건)
- [ ] 34-set·22-set 이 양방향으로 틀렸음을 기록 (거짓양성 27/34 · 거짓음성 3/10)
- [ ] **제3 관용구(수동 게이트) 발견** 기록 — 정적 grep 의 원리적 한계 실증
- [ ] `TODOS.md` 미러 drift 수치 36 → 34 한 줄 정정 (이월 항목)

## 5. 검증

- [ ] `node_modules/.bin/vitest run` 전량 green
- [ ] `node_modules/.bin/tsc -p tsconfig.app.json --noEmit`
- [ ] `node_modules/.bin/eslint src`
- [ ] `bash scripts/verify-master-plan.sh` (exit 0 · FR 139/139)
- [ ] `node scripts/build-doc-index.mjs --check`
- [ ] **가드 뮤테이션 검증** — 봉합 1건을 되돌리면 red 가 나는가 (커밋 후 `git checkout --` 로 원복)
