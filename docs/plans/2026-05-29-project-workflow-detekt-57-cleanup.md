# project-workflow detekt 57건 정리 (코드 위생 cleanup)

> slug: project-workflow-detekt-57-cleanup
> type: chore (fast-track — domain/spec/plan-review skip)
> agent: backend-engineer
> 생성: 2026-05-29

## Brief

project-workflow 모듈의 PRE_EXISTING detekt 위반 57건을 일괄 정리한다. 기능 작업과 분리된
코드 위생(lint) 정리 PR이다. PR #38에서 Maxi 승인으로 deferral된 잔여 부채.

- PR #37 선례 `detekt-baseline-module-pattern` (모듈 detekt-baseline.xml 동결 또는 실제 코드 정리)을 따른다.
- shared-kernel:detekt의 Kotlin 2.0.10 vs 1.9.25 버전충돌도 함께 점검하되, 버전충돌인지 룰위반인지 먼저 재확인한다.
- 검증은 캐시 false-green을 피하려 반드시 `./gradlew --rerun-tasks` 로 한다.
- gradle 루트는 `backend/`, 모듈 경로는 `:modules:project-workflow`.

classify 결과: type=backend로 판정됐으나 Maxi 결정으로 chore fast-track (lint 정리는 도메인/스펙/plan리뷰 부적합).

## 도메인 정리

(fast-track skip — 코드 위생 작업, 도메인 변경 없음)

## 스펙

(fast-track skip — 사용자 시나리오/FR 없음)

## Brainstorming Check

(fast-track skip)

## Plan

> TDD 변형 (lint/config chore). 새 unit test 없음. **RED = 현재 detekt FAIL**(이미 확인된 상태,
> commit message에 명시), **GREEN = `detekt --rerun-tasks` PASS**. PR #16 D5 선례 "기존 fail을 RED로 간주".
> 검증은 캐시 false-green 회피 위해 반드시 `--rerun-tasks` (메모리 `backend-detekt-lint-debt-unmasked`).

### 사실 (사전 수집 완료 — 재조사 불필요)

- `:modules:project-workflow:detekt --rerun-tasks` → FAIL, 57 weighted issues. 전부 우리 브랜치 기준 PRE_EXISTING.
  분포: MaxLineLength 34 / NestedBlockDepth 7(전부 test fixture) / LongMethod 5(test setup·seed) /
  ThrowsCount 3(main 도메인 guard) / LongParameterList 2(WorkflowScheme 애그리거트 생성자) /
  UseCheckOrError 2(test) / TooManyFunctions·CyclomaticComplexMethod·ReturnCount·SwallowedException 각 1.
- `:modules:shared-kernel:detekt --rerun-tasks` → FAIL, 룰위반 아님. "detekt was compiled with Kotlin
  2.0.10 but is currently running with 1.9.25". detekt 분석 classpath의 `kotlin-compiler-embeddable`이
  `2.0.10 → 1.9.25`로 강등(`{strictly 2.0.10}`조차 덮임). 원인: shared-kernel만 `org.springframework.boot`
  플러그인 대신 Spring BOM을 직접 import → BOM이 detekt configuration의 Kotlin까지 1.9.25로 강등.
  나머지 3개 모듈(project-workflow·identity-access·issue-tracking)은 2.0.10 유지.

### Task 1. project-workflow detekt baseline 동결

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/build.gradle.kts`, `backend/modules/project-workflow/detekt-baseline.xml`]
- depends-on: []

**RED** (기존 fail = RED).
- `./gradlew :modules:project-workflow:detekt --rerun-tasks` → BUILD FAILED, 57 weighted issues.
- 이 상태가 RED. commit message에 "RED: project-workflow detekt 57 weighted issues (PRE_EXISTING)" 명시.

**GREEN**.
- `backend/modules/project-workflow/build.gradle.kts`에 detekt baseline 블록 추가 (identity-access:28-32 선례 그대로).
  ```kotlin
  // detekt — PRE_EXISTING 위반 57건을 detekt-baseline.xml 로 동결 (PR #38 deferral 정리 PR).
  // 신규 코드는 baseline 에 포함하지 않고 코드/@Suppress 로 해소한다. baseline 의 점진적 축소는 후속.
  detekt {
      baseline = file("detekt-baseline.xml")
  }
  ```
- baseline 생성: `./gradlew :modules:project-workflow:detektBaseline` → `detekt-baseline.xml` 작성.
  (detekt 1.23.7은 `--baseline` CLI 미지원, extension + detektBaseline task가 정석 — 메모리 `detekt-baseline-module-pattern`.)

**REFACTOR**.
- 생성된 baseline.xml에 57건이 모두 들어갔는지 확인(누락 0). 주석 문구 identity-access 패턴과 일관.

**검증**. `./gradlew :modules:project-workflow:detekt --rerun-tasks` → BUILD SUCCESSFUL (0 issue, baseline 흡수).

### Task 2. shared-kernel detekt 버전충돌 fix + (드러나는 위반 동결)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/build.gradle.kts`, `backend/modules/shared-kernel/detekt-baseline.xml`]
  (detekt-baseline.xml은 fix 후 룰위반이 드러날 때만 생성 — 위반 0이면 미생성)
- depends-on: []   # project-workflow와 다른 모듈·다른 파일, 코드 의존 없음

**RED** (기존 fail = RED).
- `./gradlew :modules:shared-kernel:detekt --rerun-tasks` → BUILD FAILED, Kotlin 2.0.10 vs 1.9.25 버전충돌.
- 이 상태가 RED. commit message에 "RED: shared-kernel detekt Kotlin version mismatch (2.0.10 vs 1.9.25)" 명시.

**GREEN**.
- `backend/modules/shared-kernel/build.gradle.kts`에 detekt configuration의 Kotlin 버전 고정 추가 (detekt 공식 권장).
  ```kotlin
  // detekt 1.23.7 은 Kotlin 2.0.10 으로 컴파일됨. shared-kernel 은 org.springframework.boot 플러그인
  // 대신 Spring BOM 을 직접 import 하므로, BOM 이 detekt 분석 classpath 의 kotlin-compiler-embeddable 을
  // 1.9.25 로 강등시킨다(나머지 3개 모듈은 boot 플러그인 경유로 자동 회피). detekt configuration 에 한해
  // detekt 가 지원하는 Kotlin 버전으로 고정해 충돌을 해소한다. (https://detekt.dev/docs/gettingstarted/gradle)
  configurations.matching { it.name == "detekt" }.all {
      resolutionStrategy.eachDependency {
          if (requested.group == "org.jetbrains.kotlin") {
              useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
          }
      }
  }
  ```
  (build 상단에 `import io.gitlab.arturbosch.detekt.getSupportedKotlinVersion` 또는 FQN 사용.)
- fix 후 `./gradlew :modules:shared-kernel:detekt --rerun-tasks` 실행 → 이제 detekt가 돈다.
  - **위반 0** → 그대로 GREEN. baseline 불필요.
  - **위반 N건 출현** → Task 1과 동일하게 `detekt { baseline = file("detekt-baseline.xml") }` 추가 +
    `:modules:shared-kernel:detektBaseline` 생성 (Maxi 결정: 드러나는 위반도 baseline 동결).

**REFACTOR**.
- resolutionStrategy 블록 주석이 "왜 이 모듈만 필요한지"를 명확히 설명하는지 확인(다음 세션 회귀 방지).

**검증**. `./gradlew :modules:shared-kernel:detekt --rerun-tasks` → BUILD SUCCESSFUL.

### 최종 통합 검증 (controller, wave 종료 후)

```bash
./gradlew :modules:project-workflow:detekt :modules:shared-kernel:detekt --rerun-tasks
# 둘 다 BUILD SUCCESSFUL 확인 (ground truth, 캐시 false-green 회피)
```
- baseline 동결은 소스 코드 무수정 → 테스트 영향 없음. shared-kernel build 변경은 detekt configuration 한정 →
  compile/test 무영향. 그래도 안전 위해 두 모듈 컴파일 깨짐 없는지 확인.

## Plan 메타

- task 수: 2
- 예상 시간: task × 3분 = 약 6분 (병렬 wave 시 약 3분, 단 2-task라 순차도 무방)
- TDD 강제: yes (변형 — 기존 detekt fail을 RED로 간주, PR #16 D5 선례)
- 병렬 dispatch: Task 1·2 파일/모듈 안 겹침 + depends-on [] → 단일 wave 2-병렬 가능
- 추가 검증: 두 모듈 detekt `--rerun-tasks` PASS (controller ground truth)
- fast-track: domain/spec/plan-review skip, 게이트 1에서 Maxi 직접 검토

## 리뷰 결과

(fast-track skip — plan 리뷰 생략, 게이트 1에서 Maxi 직접 검토)

(fast-track skip — plan 리뷰 생략, 게이트 1에서 Maxi 직접 검토)
