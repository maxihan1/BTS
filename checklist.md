# 체크리스트 — 인박스 알림 → 댓글 딥링크 + 댓글 E2E TipTap 정합

## ① 딥링크 (인박스 알림 → 해당 댓글)

### 백엔드 (notification BC · T2)
- [x] B1 `NotificationSourceEvent` 에 `commentId: UUID?` 추가
- [x] B2 `NotificationWorker.buildSourceEvent` — `commentId` 파싱
- [x] B3 `NotificationWorker.buildNotification` — `payload` 에 `{"commentId":"…"}` 기록 (현재 항상 null)
- [x] B4 `InboxItemResponse` — payload 에서 `commentId` 추출해 응답 필드로 노출
- [x] B5 backend 테스트 red→green

### 프론트 (apps/web · T1)
- [x] F1 `inboxItemSchema` 에 `commentId` 추가
- [x] F2 `issueDetailModalStore.open(issueKey, commentId?)` — `focusCommentId` 보관
- [x] F3 `/issues/$key?comment=<uuid>` search param (새 탭·딥링크 경로)
- [x] F4 `InboxListItem` — 링크·모달 양쪽에 commentId 전달
- [x] F5 `IssueDetailPage` — `focusCommentId` 로 댓글 탭 활성 + 스크롤 + 하이라이트
- [x] F6 `CommentSection` — 대상 댓글 anchor/하이라이트 수용
- [x] F7 MSW fixture · 단위 테스트

## ② 댓글 E2E TipTap 정합 (T1)
- [x] E1 `issue-comment.spec.ts` — `toHaveValue('')` (input 전용) → contenteditable 비움 검증

## 게이트
- [ ] `./gradlew :modules:notification:test`
- [ ] `pnpm --filter web test` · `typecheck` · `lint`
- [ ] `pnpm --filter web test:e2e -- issue-comment` (4/4 green)
