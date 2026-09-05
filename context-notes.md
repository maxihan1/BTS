# 컨텍스트 노트 — 인박스 댓글 딥링크

## 2026-09-05 · 착수 시점 실측

### 이미 있는 것
- `IssueDomainEvent.IssueMentioned.commentId` / `IssueCommented.commentId` — 이벤트 페이로드에 이미 실려 있다.
- `notifications.payload JSONB` 컬럼 — V402 주석이 **"원본 이벤트 컨텍스트 JSONB (Inbox 딥링크/재렌더링용)"**.
  즉 딥링크는 처음부터 이 컬럼을 쓰라고 설계돼 있었다.
- `NotificationRepository.toDomain` 은 `payload = record.payload?.data()` 로 이미 왕복 보존한다.

### 빠져 있던 것 (이번 작업의 실제 결손)
- `NotificationWorker.buildNotification` 이 **`payload = null` 을 하드코딩**한다 (라인 374).
  컬럼도 있고 왕복도 되는데 **쓰는 쪽이 없어서** 딥링크가 성립하지 않았다.
- `NotificationSourceEvent` 에 `commentId` 필드가 없어 파싱 자체를 안 한다.
- `InboxItemResponse` 에 `commentId` 가 없어 API 로 나가지 않는다.

## 결정

### D1 — 새 컬럼(`comment_id`) 대신 기존 `payload JSONB` 를 쓴다
새 컬럼은 타입이 붙고 인덱싱이 되지만 마이그레이션 = **T3**(ADR+plan) 로 티어가 뛴다.
`payload` 는 V402 주석이 딥링크 용도를 명시하고 왕복 배관이 이미 있다. 딥링크는 **목록에서
같이 딸려오는 값**이지 조회 조건이 아니므로 인덱스가 필요 없다. → payload 채택, T2 유지.

### D2 — 파싱 위치는 HTTP 경계(`InboxItemResponse`)
`Notification.payload` 는 도메인에선 불투명한 문자열로 둔다. 도메인에 JSON 파서를 들이면
"payload 의 스키마" 를 도메인이 알게 되고, 채널마다 다른 payload 를 넣는 순간 갈라진다.
응답 DTO 가 자기 응답 필드를 만들기 위해 파싱하는 형태가 결합이 가장 얕다.

### D3 — 딥링크는 모달·전체화면 **양쪽** 에서 동작해야 한다
인박스의 이슈 링크는 평범한 좌클릭이면 `useOpenIssueDetail` 이 가로채 **모달**로 열고,
⌘클릭이면 `/issues/$key` 새 탭으로 간다(J1 계약). 한쪽만 배선하면 "새 탭으로 열면 댓글로
안 간다" 가 된다. 그래서 `IssueDetailPage` 에 `focusCommentId` prop 을 두고
모달 스토어와 라우트 search param 이 **같은 prop 으로 수렴**하게 한다.

## ② 댓글 E2E 실패 — 원인 확정
`issue-comment.spec.ts:75` `await expect(textarea).toHaveValue('')` →
`Error: Not an input element`. 댓글 입력은 J8 에서 TipTap(contenteditable)으로 이미 넘어갔고
(`CommentSection` → `RichTextEditor`), `toHaveValue` 는 input/textarea/select 전용이다.
`fill()` 은 contenteditable 에도 동작해서 나머지 3건은 통과 — **마지막 단언 1줄만** 낡았다.

## 2026-09-05 · 구현 중 결정

### D4 — 딥링크는 「탭 열기」가 절반이다
Radix Tabs 가 비활성 탭을 **언마운트**하므로, 댓글 탭이 닫힌 채로는 스크롤할 행이 DOM 에
아예 없다. 그래서 `focusCommentId` 는 스크롤 대상이자 **초기 탭 결정자**다.
초기값(useState)과 effect 를 **둘 다** 둔다 — 초기값만 두면 모달이 열린 채 다른 알림을
눌렀을 때(`focusCommentId` 만 교체) 못 따라가고, effect 만 두면 첫 프레임이 이력 탭이라
그 사이 댓글 목록이 마운트되지 않아 스크롤할 것이 없다.

### D5 — 200줄 래칫에 걸려 훅으로 분리
`IssueDetailPage` 가 1012 → 1024 로 커져 `lint-ratchet` 이 red. 규칙(DRIFT_HINT)이
「늘렸다면 되돌리거나 쪼개라」이므로 `useActivityTab` 훅으로 뽑았다. 결과 **1010** 으로
오히려 줄어 베이스라인도 1012 → 1010 으로 낮췄다(줄인 쪽도 red 인 래칫이다).

### D6 — 단위 테스트의 Link 대역이 search 를 삼키고 있었다
`InboxListItem.test.tsx` 의 `Link` 대역이 `search` prop 을 받지 않아, 「새 탭에도 딥링크가
간다」는 단언이 **대역의 침묵으로 항상 통과**할 뻔했다. 대역이 search 를 href 에 반영하도록
고쳤다. 계열 메모리 `mock-swallowed-prop-is-invisible-to-unit-tests`.

### D7 — jsdom 에는 scrollIntoView 가 없다
`rowRef.current?.scrollIntoView?.({...})` 로 옵셔널 호출한다. 그러면 테스트에서 조용히
통과하므로, 스크롤 검증 테스트는 `Element.prototype.scrollIntoView` 를 직접 심어 호출을
실측한다 — 심지 않으면 「스크롤한다」를 아무도 검증하지 않는다.

### 남은 것 (이번 범위 밖)
- 실시간 STOMP 푸시 payload(`InAppNotificationPayload`)에는 commentId 를 싣지 않았다.
  인박스 목록 경로만으로 딥링크가 성립하고, 푸시는 클릭 대상이 아니라 토스트다.
