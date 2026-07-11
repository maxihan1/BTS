# automation 모듈을 :modules:app prod 조립에 추가하고 조립 앱 부팅 검증

> slug: automation-prod-assembly
> type: feature
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-11

## Brief

사용자 원문: "automation 모듈을 :modules:app prod 조립에 추가하고 조립 앱 부팅 검증"

FR-AT-02(#256, 2026-07-11 머지)의 **C4 후속**. automation BC(FR-AT-01 트리거 + FR-AT-02 액션)가 prod 조립 앱 `:modules:app`(#253)에 미배선 상태. app `build.gradle.kts`가 8개 BC 모듈만 의존하고 `:modules:automation`을 빠뜨려, automation의 pgmq consumer·@Scheduled 워커·cross-BC `IssueMutationPort` 결선이 prod에서 미가동.

**핵심 발견 (context-restore 체크포인트 + history #256)**: FR-AT-02 worktree가 #253 이전 base라서 C4 ADR이 "전역 조립 모듈은 타 BC 미구현 어댑터로 부팅불가"로 판단·이연했으나, `:modules:app`은 이미 8모듈·prod 프로파일로 존재·부팅. 따라서 이 작업은 "모듈 신설"이 아니라 **"기존 app에 automation 의존 추가 + 부팅 검증"**.

**리스크 (검증 대상)**: automation이 prod 컨텍스트에서 처음 결선될 때 미구현 어댑터/미설정 env를 요구하는가. 관련 함정 — [[no-cross-bc-deployment-assembly]] · [[profile-scoped-bean-boot-failure]] · [[minio-eager-bean-fullboot-regression]] · [[new-crossbc-dep-openapi-mockbean-regression]] · [[module-first-scheduled-worker-detektmain-traps]] · [[use-time-validated-env-passes-boot-fails-on-use]].

classify: type=feature, agent=backend-engineer, primary_bc=automation

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
