# FR-AT-07 PR-C — PR_MERGED 트리거 + Git Webhook 인바운드

> slug: fr-at-07-pr-c-git-webhook
> type: auth
> agent: security-engineer
> 생성: 2026-07-17
> 브랜치: auth/fr-at-07-pr-c-git-webhook (base: origin/main 552ef6a5a)

## Brief

**사용자 원문**. "fr-at-07 pr-c 진행해줘"

**범위**. FR-AT-07의 남은 D단계 전부 — PR-A(#274/#275 인바운드 웹훅 prod 도달 + 암호화 키 배포)와
PR-B(#276 Fix Version 설정 통로)가 완료된 상태에서, PR 머지가 실제로 자동화 규칙을 발화시키는
마지막 경로를 잇는다.

- D1. 도메인 — GitWebhookEvent
- D2. 명세 — GitHub/GitLab Webhook 처리 + 커밋 메시지 이슈 키 추출
- D3. 데이터 모델 — webhook secret 저장 (활용, 신규 스키마 여부는 spec에서 확정)
- D4. 백엔드 — `POST /api/v1/webhooks/git` + 서명 검증 + automation permitAll 중앙등록
- D5. 백엔드 테스트 — 가짜 페이로드
- D6. 프론트 UI — Webhook URL 생성 페이지
- D7. E2E

**완료 시**. FR-AT-07 `[x]` 마킹 → automation BC **7/7 완결** (9번째 모듈 전 FR 완료).

**classify 결과 및 덮어쓰기 근거**.
스크립트 원본 판정은 `type=ui / agent=frontend-engineer / slug=fr-at-07-pr-c-pr-merged-git-webhook-automation-per`.
제목의 "UI" 토큰에 끌린 오분류로 판단해 Maxi 확인 후 **type=auth / agent=security-engineer**로 덮어씀.
근거 — 이 PR의 폭발 반경 최대 지점은 인증 없이 열리는 인바운드 엔드포인트(permitAll)와 HMAC 서명
검증이며, UI는 D6 하나. slug도 잘린 채(`-per`) 생성돼 선행 PR 관례(`fr-at-07-pr-b-fix-version`)에 맞춰
`fr-at-07-pr-c-git-webhook`으로 축약.
`.bts-cache/classify.json`은 멀티세션 충돌 이력이 있어 `--cache` 미사용. **본 plan이 분류의 진실 출처**.

**동시 진행 작업**. draft PR #277 (프로젝트 관리 CRUD, `.worktrees/project-management-crud`)이 별도
세션에서 domain 단계 진행 중. 본 작업과 파일 영역 교집합 여부는 spec 단계에서 확인.

## 선행 함정 (메모리 인계 — spec/plan 단계에서 전수 반영)

- `permitall-opens-preexisting-body-buffer-dos` — permitAll은 서명 검증 **전에** 힙에 본문을 적재.
  `@RequestBody String`이면 secret 없이도 미인증 DoS. #275의 `readBoundedSlackBody` 선례 확인 필요.
- `bearer-token-resolver-drains-form-body` — form POST에서 `access_token` 조회가 Tomcat 파싱을
  트리거해 바디가 빈 채로 도달. GitLab/GitHub 페이로드 형식(json vs form-encoded) 확인 필수.
- `negative-guard-needs-body-discriminator` — "여전히 401"류 음성 가드는 vacuous. 위반을 주입해
  fail 확인.
- `prod-assembly-boot-verification-required` — cross-BC `@Component` 추가 시 머지 전 rebase +
  `:modules:app:test` 9BC prod 조립 재검증.
- `preseeded-event-producer-activates-notifications` — 신규 이벤트 발행 전 enum/시드 grep.
- `no-backend-ci-and-assembly-merge-verification-traps` — 백엔드 CI 부재. 로컬 검증이 유일 관문.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
