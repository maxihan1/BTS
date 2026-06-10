<!-- BTS 전용 pre-landing 리뷰 체크리스트 — /review(gstack) Step 2가 읽는 프로젝트 정본 -->
# BTS Pre-Landing Review Checklist

## Instructions

- diff 기준은 `origin/main` (merge-base). 지적은 `file:line` 인용 + 수정 제안. 문제 없는 항목은 침묵.
- 진행 순서. **Pass 0 (BTS 전제 절차) → Pass 1 (CRITICAL) → Pass 2 (INFORMATIONAL)**.
- 출력 형식은 글로벌 체크리스트와 동일.

```
Pre-Landing Review: N issues (X critical, Y informational)

**AUTO-FIXED:**
- [file:line] 문제 → 적용한 수정

**NEEDS INPUT:**
- [file:line] 문제 설명
  Recommended fix: 제안
```

이슈 없으면 `Pre-Landing Review: No issues found.` 한 줄. 서론/총평 금지.

## Pass 0 — BTS 전제 절차 (카테고리 검사 전 의무 실행)

1. **PRE_EXISTING 판별** — 위반을 지적하기 전에 `git show origin/main:<file>`로 main에 이미 있던 코드인지 대조. PRE_EXISTING이면 출력에 표기만 하고 이 PR을 차단하지 않는다 (hot-fix 혼입 금지).
2. **정적 분석 실검증** — detekt는 빌드 캐시가 위반을 가리므로 `./gradlew detekt --rerun-tasks`로만 신뢰. 모듈 전체 `ktlintFormat` 실행 절대 금지 (의도 안 한 부수 변경 + 캐시 오염) — `ktlintCheck` 계열만.
3. **잔여물 전수 확인** — `git status --porcelain`으로 미커밋 산출물·스크래치 파일 잔여 확인 (worktree 산출물 소실 사고 이력).
4. **FR/plan 정합** — diff에 FR 카운트·plan 파일 변경이 포함되면 `bash scripts/verify-master-plan.sh` 결과(exit 0) 확인.

## Pass 1 — CRITICAL

### 글로벌 카테고리 (위임)

`~/.claude/skills/gstack/review/checklist.md`를 Read하여 Pass 1 카테고리를 그대로 적용한다.

- SQL & Data Safety
- Race Conditions & Concurrency
- LLM Output Trust Boundary
- Shell Injection
- Enum Completeness

글로벌 파일을 읽을 수 없으면 위 카테고리 이름 기준으로 자체 검사하고, 출력에 `global checklist unavailable`을 명시한다.

### BTS 고유 CRITICAL

- **init_codegen.sql 미러** — 컬럼/테이블 추가 마이그레이션이 `init_codegen.sql`에 동일하게 미러됐는지 (Flyway↔jOOQ 코드젠 분리 구조 — 누락 시 repository 컴파일 실패).
- **Flyway V번호 충돌** — 새 마이그레이션 V번호가 origin/main 최신 V번호와 충돌하지 않는지 머지 직전 기준으로 확인 (동시 브랜치 checksum 충돌).
- **도메인 예외 핸들러 스코프** — 새 `@ExceptionHandler`/`@RestControllerAdvice`가 타 컨트롤러 응답 코드를 변질시키지 않는지 (catch-all이 `ResponseStatusException`을 삼켜 401→500 변질 사고 이력).
- **직렬화↔Zod 정합** — `@JsonInclude(NON_NULL)` 등 백엔드 직렬화 설정과 프론트 Zod 스키마의 optional/nullable이 어긋나지 않는지.
- **jOOQ cartesian product** — 다중 LEFT JOIN + count/집계 조합. 연관 카운트는 스칼라 서브쿼리로 분리됐는지.
- **권한 fail-open** — 권한 resolver/가드의 nullable 기본값·`?: return`·미주입 빈이 "허용"으로 떨어지는 경로가 없는지. 불명은 거부가 기본.

## Pass 2 — INFORMATIONAL

### 글로벌 카테고리 (위임)

같은 글로벌 파일의 Pass 2 (INFORMATIONAL) 카테고리를 적용한다.

### BTS 고유

- 빈 catch 블록 (최소 로그 + rethrow 또는 명시 처리).
- KDoc/주석의 책임 선언 ↔ 실제 구현 불일치.
- plan↔spec 표기 drift — 불일치 발견 시 spec이 정본임을 표기 (plan은 축약본).
- 에러 코드 prefix 규약 (`ISSUE_` / `WORKFLOW_` / `AUTOMATION_` / `NOTIF_` / `SLACK_`) 위반.
- 사용자 노출 문자열의 i18n 키 누락 (하드코딩 한국어/영어 리터럴).

## Severity / Fix-First

글로벌 체크리스트의 Severity Classification과 Fix-First Heuristic을 그대로 따른다 (기계적 수정은 자동 적용, 모호한 것은 질문 일괄 배치).

## Suppressions — DO NOT flag

- 글로벌 체크리스트의 Suppressions 전체 적용.
- `detekt-baseline.xml` 동결 항목 (identity-access · issue-tracking · project-workflow 3모듈 — PRE_EXISTING 동결 정책).
- Pass 0에서 PRE_EXISTING으로 판별된 위반 (표기만, 차단 금지).
- `packages/` · `features/` 모노레포 부재 지적 (단일 SPA가 의도된 구조).
- Testcontainers 공통 베이스 클래스 부재 (알려진 현황, 후속 트랙).
