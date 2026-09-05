# 인박스 알림 → 댓글 딥링크 + 댓글 E2E TipTap 정합 (FR-UX-03 · FR-CO-01)

> 티어: T2
> slug: inbox-comment-deeplink
> type: feature
> agent: backend-engineer
> 생성: 2026-09-05

## Brief

**Maxi 원문** — 「① 인박스 알림을 눌렀을 때 해당 댓글로 이동하는 딥링크 추가 ② 기존
issue-comment.spec.ts 1건 실패는 TipTap 진행」

**티어 판정** — `backend/modules/notification/**/main`(BE_MAIN)과 `apps/web/src/router.ts`
(보안 표면 — 라우트 가드가 사는 파일)를 함께 건드리므로 **T2** 다. 섞이면 최고 티어 규칙.
`router.ts` 변경은 `/issues/$key` 에 `validateSearch` 한 줄을 더하는 것뿐이고
`beforeLoad: requireAuthAndPasswordChanged` 가드는 손대지 않는다.

**BC** — notification 단일. `NotificationSourceEvent`·`InboxItemResponse` 를 참조하는
모듈이 notification 밖에 없음을 grep 으로 확인했다(한 PR = 한 BC 유지).

## 착수 시점 실측

### 이미 있던 것
- `IssueDomainEvent.IssueMentioned.commentId` / `IssueCommented.commentId` — 이벤트
  페이로드에 **이미 실려 온다**.
- `notifications.payload JSONB` — V402 주석이 「원본 이벤트 컨텍스트 JSONB
  (Inbox 딥링크/재렌더링용)」로 정의한 자리다. 딥링크는 처음부터 이 컬럼을 쓰라고 설계돼 있었다.
- `NotificationRepository.toDomain` 이 `payload = record.payload?.data()` 로 왕복 보존한다.

### 빠져 있던 것 — 이번 작업의 실제 결손
- `NotificationWorker.buildNotification` 이 **`payload = null` 을 하드코딩**한다.
  컬럼도 있고 왕복도 되는데 **쓰는 쪽만 없어서** 딥링크가 성립하지 않았다.
- `NotificationSourceEvent` 에 `commentId` 필드가 없어 파싱 자체를 안 한다.
- `InboxItemResponse` 에 `commentId` 가 없어 API 로 나가지 않는다.

## 결정

### D1 — 새 컬럼(`comment_id`) 대신 기존 `payload JSONB`
새 컬럼은 타입이 붙고 인덱싱이 되지만 마이그레이션 = **T3**(ADR+plan)로 티어가 뛴다.
딥링크는 **목록에 딸려 나오는 값**이지 조회 조건이 아니라 인덱스가 필요 없고, V402 주석이
용도를 명시하며 왕복 배관이 이미 있다. → payload 채택, T2 유지.

### D2 — 파싱 위치는 HTTP 경계(`InboxItemResponse`)
`Notification.payload` 는 도메인에선 불투명한 문자열로 둔다. 도메인에 JSON 파서를 들이면
「payload 의 스키마」를 도메인이 알게 되고, 채널마다 다른 payload 를 넣는 순간 갈라진다.

### D3 — 딥링크는 모달·전체화면 **양쪽**에서 동작해야 한다
인박스의 이슈 링크는 평범한 좌클릭이면 `useOpenIssueDetail` 이 가로채 **모달**로 열고,
⌘클릭이면 `/issues/$key` 새 탭으로 간다(J1 계약). 한쪽만 배선하면 「새 탭으로 열면 댓글로
안 간다」가 된다. `IssueDetailPage.focusCommentId` 하나로 모달 스토어와 라우트 search
param 이 수렴한다.

### D4 — 딥링크는 「탭 열기」가 절반이다
Radix Tabs 가 비활성 탭을 **언마운트**하므로 댓글 탭이 닫힌 채로는 스크롤할 행이 DOM 에
아예 없다. 초기값(useState)과 effect 를 **둘 다** 둔다 — 초기값만 두면 모달이 열린 채 다른
알림을 눌렀을 때(`focusCommentId` 만 교체) 못 따라가고, effect 만 두면 첫 프레임이 이력
탭이라 그 사이 목록이 마운트되지 않아 스크롤할 것이 없다.

### D5 — 200줄 래칫에 걸려 훅으로 분리
`IssueDetailPage` 가 1012 → 1024 로 커져 `lint-ratchet` 이 red. 규칙(DRIFT_HINT)이
「늘렸다면 되돌리거나 쪼개라」이므로 `useActivityTab` 훅으로 뽑았다. 결과 **1010** 으로
오히려 줄어 베이스라인도 1012 → 1010 으로 낮췄다(줄인 쪽도 red 인 래칫이다).

### D6 — 실을 것이 없으면 `{}` 가 아니라 null
빈 객체를 넣으면 「딥링크 없음」과 「딥링크 유실」을 소비 측에서 구분할 수 없고, payload
존재만 보고 링크를 그리게 된다.

### D7 — payload 파싱은 어떤 입력에도 던지지 않는다
V402 이후 모든 기존 행이 payload NULL 이다. 깨진 한 건이 알림 목록 전체를 500 으로
만들어선 안 된다.

## ② 댓글 E2E 실패 — 원인과 함께 드러난 가짜 그린

`issue-comment.spec.ts:75` `await expect(textarea).toHaveValue('')` →
`Error: Not an input element`. 댓글 입력은 J8 에서 TipTap(contenteditable)으로 넘어갔고
`toHaveValue` 는 input/textarea/select 전용이다.

**한 줄 수정으로 끝나지 않았다.** contenteditable 은 **작성 중인 초안이 DOM 텍스트로
존재**하므로 `section.getByText(본문)` 이 에디터 자신을 잡는다. textarea 시절 초안은 value 라
안 잡혔다. 즉 「목록에 반영됐다」 단언이 **목록을 보지 않고도 통과**할 수 있었다. 목록 검증을
`data-testid=comment-body-*` 로 좁혀 저장된 댓글만 세게 했다 — 이 스코프 없이는 연속 작성
테스트가 저장 중(`contenteditable=false`)에 다음 fill 을 때려 실제로 red 가 된다.

## Task

### 백엔드 (notification BC)
- [x] B1 `NotificationSourceEvent.commentId: UUID?`
- [x] B2 `NotificationWorker.buildSourceEvent` — `commentId` 파싱
- [x] B3 `NotificationWorker.buildNotification` — `payload` 에 `{"commentId":"…"}` 기록
- [x] B4 `InboxItemResponse.commentId` — payload 에서 추출해 노출
- [x] B5 red→green (컴파일 red 확인 → 구현 → 7+31 green)

### 프론트 (apps/web)
- [x] F1 `inboxItemSchema.commentId` (UUID 아니면 파싱 거부)
- [x] F2 `issueDetailModalStore.open(issueKey, commentId?)` + `useOpenIssueDetail` 3번째 인자
- [x] F3 `/issues/$key?comment=<uuid>` search param
- [x] F4 `InboxListItem` — 링크·모달 양쪽에 전달
- [x] F5 `IssueDetailPage.focusCommentId` → 댓글 탭 활성 (`useActivityTab`)
- [x] F6 `CommentSection` — 대상 행 강조 + 스크롤
- [x] F7 MSW fixture · 단위 테스트 · E2E-10/11

### ② E2E
- [x] E1 `issue-comment.spec.ts` — contenteditable 정합 + 목록 스코프

## 게이트 결과

- `./gradlew :modules:notification:test ktlintCheck detekt` — BUILD SUCCESSFUL
- `vitest run` (apps/web 전량) — **657 파일 / 10,888 테스트 green**
- `playwright issue-comment.spec.ts` — 4/4 · `inbox.spec.ts -g E2E-10|E2E-11` — 2/2
- `build-doc-index --check` · `verify-master-plan.sh` — PASS

### 뮤테이션(비-공허) 확인
- `payload = buildPayload(event)` → `null` 로 끊으니 payload 테스트 1건만 정확히 red.
- `isFocusTarget={comment.id === focusCommentId}` → `false` 로 끊으니 강조·스크롤 2건 red.

## 함정 기록

- **단위 테스트의 `Link` 대역이 `search` 를 삼키고 있었다.** 그대로 두면 「새 탭에도 딥링크가
  간다」가 대역의 침묵으로 항상 통과한다. 대역이 search 를 href 에 반영하게 고쳤다.
  계열 메모리 `mock-swallowed-prop-is-invisible-to-unit-tests`.
- **jsdom 에는 `scrollIntoView` 가 없다.** `?.` 옵셔널 호출이 조용히 지나가므로, 스크롤 검증
  테스트는 `Element.prototype.scrollIntoView` 를 직접 심어 호출을 실측한다.
- **worktree 에 `node_modules` 가 없어 pnpm 래퍼가 죽는다**(`ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY`).
  main 트리에서 심볼릭 링크하고, `pnpm test:workflow` 대신 `node --test` 를 직접 부른다.

## 범위 밖

- 실시간 STOMP 푸시 payload(`InAppNotificationPayload`)에는 commentId 를 싣지 않았다.
  인박스 목록 경로만으로 딥링크가 성립하고, 푸시는 클릭 대상이 아니라 토스트다.
