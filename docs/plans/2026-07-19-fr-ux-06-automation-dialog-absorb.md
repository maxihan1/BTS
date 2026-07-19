# FR-UX-06 Phase 2 — automation Dialog 흡수 (9파일)

> slug: fr-ux-06-automation-dialog-absorb
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-19

## Brief

**원문 요청**. automation 컴포넌트 디렉토리(`apps/web/src/components/automation/`)의 Dialog 9개를 CloneIssueDialog 정본 패턴으로 공용 `ui/dialog` compound 래퍼에 흡수. 순수 render-shell 리팩터라 기능·FR 불변(129). FR-UX-06 Dialog 흡수 4분할의 다음 단계이며 PR8(ESLint 락)과는 분리된 자체 PR.

**classify**. type=ui, agent=frontend-engineer, primary_bc=issue-tracking(오탐 — 실제는 apps/web 순수 프론트).

### 실측 스코프 (개수 눈가리개 방어 — 직접 전수 열거)

흡수 대상 9파일 (모두 `import { Dialog as DialogPrimitive } from 'radix-ui'` umbrella 사용, `@radix-ui/react-dialog` 아님):
1. `AutomationRuleFormDialog.tsx`
2. `AutomationRuleList.tsx` — file-local `DeleteConfirmDialog`
3. `AutomationYamlImportDialog.tsx`
4. `GitWebhookRegisterDialog.tsx`
5. `GitWebhookSection.tsx` — file-local `DeleteConfirmDialog`
6. `GitWebhookUrlModal.tsx` — `CloseConfirmPrompt` 서브구조 포함
7. `RuleConflictWarningModal.tsx`
8. `RuleExecutionHistoryDialog.tsx`
9. `WebhookTokenModal.tsx`

각 파일 = radix Dialog.Root 1개. 흡수 대상 아님: ActionConfigEditor·ActionListEditor·ConditionBuilder·ConditionComparisonRow·ProjectMemberSelect·RuleExecutionTraceRow (radix Dialog 미사용).

### 정본 API (흡수 대상 계약)

정본 소스 = `apps/web/src/components/issues/CloneIssueDialog.tsx`.
`ui/dialog.tsx` export 심볼: `Dialog · DialogTrigger · DialogPortal · DialogOverlay · DialogContent · DialogClose · DialogHeader · DialogFooter · DialogTitle · DialogDescription`.

### ★핵심 리스크 (auth PR7 대비 신규)

- **load-bearing 닫기 방지(token-at-risk) 로직** — WebhookTokenModal·GitWebhookUrlModal·RuleConflictWarningModal·AutomationYamlImportDialog는 `onOpenChange`를 가로채 X·ESC·오버레이·onOpenChange 닫기 4경로를 제어(비밀토큰 1회 노출 보호). 래퍼 기본 닫기 동작이 이 로직을 깨면 회귀. 흡수 시 `onOpenChange` 위임 정확성 실측 필수.
- **함정 6종 실측**(FR-UX-06 선례) — ①sm:justify-between ②Cancel onClick 이중닫힘 ③prop명 매핑 ④test 경로 flat vs __tests__/ ⑤DialogDescription aria ⑥DialogTrigger 유지.
- **CI에 e2e(playwright) 잡 없음** — 머지 전 로컬 e2e 필수.

## 도메인 정리

- BC: 없음 (apps/web 프론트엔드 presentation-layer 리팩터. 백엔드 도메인 모델 무관).
- 영향 엔티티: 없음. 신규 용어/유비쿼터스 언어: 없음.
- 성격: 순수 render-shell 흡수 — radix Dialog primitive → 공용 `ui/dialog` compound 래퍼. 동작·데이터·API 불변.
- 기존 결정 충돌: 없음.
- 관련 ADR: [docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) — **부모 승인 결정**(FR-UX-06 개편 Phase 2 Dialog 흡수). 본 작업은 그 하위 실행 단계이며 신규 결정 아님.
- grill-with-docs: 도메인 무영향 리팩터라 no-op 처리(PR5/6/7 동일 선례). 대화형 grill 생략.

## 스펙

전체 스펙. [docs/specs/2026-07-19-fr-ux-06-automation-dialog-absorb.md](../specs/2026-07-19-fr-ux-06-automation-dialog-absorb.md)

핵심 요약.
- 9파일 흡수 확정(Maxi 2026-07-19). **Tier A 7파일**(직관적, 사실상 diff-0) + **Tier B 2파일**(GitWebhookUrlModal·AutomationYamlImportDialog — 보안 크리티컬 닫기 가로채기 + overlay 테스트 재작성).
- 정본 = CloneIssueDialog(일반) + PatTokenModal(token-at-risk). WebhookTokenModal은 PatTokenModal 동형.
- Tier B: `onEscapeKeyDown`/`onPointerDownOutside`/testid를 `DialogContent`에 prop 전달({...props} forward), overlay testid 유실분은 `[data-slot="dialog-overlay"]` 선택자로 유닛 테스트 재작성.
- 머지 전 로컬 e2e 필수(CI에 playwright 잡 없음).

## Brainstorming Check

✅ 통과 (1회 iteration). Tier B overlay testid 유실 + 보안 가로채기 gap → 스코프 결정(9파일)으로 해소.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
