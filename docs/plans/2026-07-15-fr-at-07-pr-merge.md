# FR-AT-07 — PR 머지 연동 (Fix Version 자동 설정) · 백엔드 D1~D5

> slug: fr-at-07-pr-merge
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-15

## Brief

**사용자 원문**. `fr-at-07 진행해줘`

**작업 범위**. FR-AT-07(PR 머지 연동 — Fix Version 자동 설정)의 **D1~D5 백엔드**. UI(D6 Webhook URL 생성 페이지) + D7(E2E)은 **후속 PR로 분리**(Maxi 확정) — FR-AT-04·05·06 전부 이 방식으로 진행된 선례.

| 단계 | 내용 | 책임 |
|---|---|---|
| D1 | 도메인 — GitWebhookEvent | backend-engineer |
| D2 | 명세 — GitHub/GitLab Webhook 처리. 커밋 메시지에서 이슈 키 추출 | backend-engineer |
| D3 | 데이터 모델 — (활용. webhook secret 저장) | db-engineer |
| D4 | 백엔드 — `POST /api/v1/webhooks/git` + 서명 검증 | backend-engineer + security-engineer |
| D5 | 백엔드 테스트 — 가짜 페이로드 | backend-engineer |

**classify 결과**. type=backend / agent=backend-engineer / ~~primary_bc=issue-tracking~~ → **automation 정정**
 (classify가 "이슈키" 키워드로 issue-tracking 오판. product doc §2.7 소속은 automation.)

**우선순위**. 높음 | **선행**. §2.1 (WEBHOOK 트리거), §2.2 (액션) | **product doc Plan slug**. `automation/pr-merge`

**의의**. Phase 1의 **마지막 FR**. 완료 시 automation BC 6/7 → **7/7** (BC 완결), 전체 진척 122/123 → **123/123**.

## 열린 질문 (→ /bts-domain·/bts-spec에서 해소)

1. **cross-BC 경계**. Fix Version(`FR-VR`)은 issue-tracking 소유. automation이 이슈의 Fix Version을 설정 = BC 경계 통과. 기존 automation 액션이 이슈를 어떻게 건드리는지(포트 위임 / pgmq 이벤트) 관례 확인 필요.
2. **FR-AT-01 WEBHOOK 트리거와의 관계**. automation에 이미 WEBHOOK 트리거 + 토큰 체계 존재. Git webhook은 별도 엔드포인트인가, 기존 트리거의 특수 케이스인가.
3. **서명 검증 방식**. GitHub(`X-Hub-Signature-256`, HMAC-SHA256) vs GitLab(`X-Gitlab-Token`, 평문 비교) — 두 provider 모두 지원 범위인가.
4. **webhook secret 저장 위치**. D3이 "활용"이라 명시 — 신규 테이블 없이 기존 스키마 재사용 가능한지 확인.
5. **이슈 키 추출 규칙**. 커밋 메시지 / PR 제목 / PR 본문 중 어디까지, 다중 키 매칭 시 동작.
6. **엔드포인트 인증**. `/api/v1/webhooks/git`은 외부 Git 서버가 호출 → permitAll + 서명 검증. 중앙 등록 관례 확인(기존 부채 항목).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
