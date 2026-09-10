// backend-ci 매트릭스가 Gradle 모듈 전량을 덮는지 강제하는 정합 테스트
//
// 왜 이 테스트가 있나. Gradle 모듈은 `backend/settings.gradle.kts` 에서 자라는데, CI 가 도는
// 대상과의 정합을 아무도 보지 않으면 **새 BC 모듈이 CI 에서 조용히 빠진다** — 이 저장소가 겪은
// 실패 양식 그대로다 (CORS allowedMethods ↔ 컨트롤러 매핑, BC_KEYWORDS ↔ 도메인 어휘,
// 스킬 분기표 ↔ TaskType).
//
// 판별식. 두 목록의 **차집합이 0** 인지 본다. 새 모듈을 추가하면 이 테스트가 먼저 깨진다.
//
// ## ★비교 대상이 바뀌었다 (2026-08-12 · 부채 매핑 31)
//
// 종전에는 `backend-ci.yml` 의 **하드코딩 매트릭스 목록**을 읽었다. 그런데 그 목록은 이제
// 없다 — 매트릭스가 `select` 잡의 출력을 받는 동적 형태가 됐고, 목록을 워크플로우에 다시
// 적는 것 자체를 `select-backend-modules.test.ts` 가 금지한다.
//
// 그래서 **선별기가 고를 수 있는 전체 집합**(`allModules()`)을 비교 대상으로 삼는다. 그것이
// 「CI 가 돌 수 있는 모듈」의 정본이기 때문이다. 두 목록이 여전히 **서로 독립**이라는 점이
// 중요하다 — 한쪽은 `settings.gradle.kts` 의 `include(...)`, 다른 쪽은 `backend/modules/` 의
// 디렉터리 실물이다. 한쪽만 고치면 여기서 깨진다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { allModules } from './select-backend-modules.ts';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const SETTINGS = path.join(REPO_ROOT, 'backend/settings.gradle.kts');
// ★2026-09-09 P4 재조준. 종전에는 `.github/workflows/backend-ci.yml` 을 읽었다.
//   CI 정본이 젠킨스로 옮겨졌으므로(Jenkinsfile) 조립 부팅 배선도 그쪽에서 본다.
//   지키려던 것은 파일이 아니라 **「조립 부팅과 비-prod 가드가 어딘가에서 실제로 돈다」**이고,
//   그 자리가 바뀌었을 뿐이다. Actions 철거(P4b) 전에 옮겨 두지 않으면 파일이 사라지는
//   순간 이 단언이 red 도 안 내고 함께 사라진다.
const PIPELINE = path.join(REPO_ROOT, 'Jenkinsfile');

/** `include(":modules:xxx")` 에서 모듈명을 뽑는다 */
function gradleModules(): Set<string> {
  const src = fs.readFileSync(SETTINGS, 'utf8');
  return new Set([...src.matchAll(/include\(":modules:([a-z-]+)"\)/g)].map((m) => m[1] as string));
}

/**
 * backend-ci 가 **돌 수 있는** 모듈 전체 집합.
 *
 * 선별기가 고를 수 있는 범위가 곧 그것이다. 워크플로우에서 목록을 읽지 않는 이유는
 * 파일 헤더 §비교 대상이 바뀌었다 참조 — 이제 그 목록이 존재하지 않는다.
 */
function ciCoveredModules(): Set<string> {
  return new Set(allModules());
}

/** 매트릭스 밖에서 별도 잡으로 도는 모듈 (조립 부팅은 전용 postgres 가 필요해 분리돼 있다) */
const SEPARATE_JOB_MODULES = new Set(['app']);

describe('backend-ci 매트릭스 ↔ Gradle 모듈 정합', () => {
  test('판별식이 비어 있지 않다 (양성 대조군)', () => {
    const modules = gradleModules();
    const matrix = ciCoveredModules();

    // 하한이 없으면 파서가 0건을 내도 아래 차집합이 공허하게 통과한다.
    // 0 이라는 결과는 "없다" 가 아니라 "내 판별식이 틀렸다" 를 먼저 의심해야 한다.
    assert.ok(modules.size >= 5, `settings.gradle.kts 에서 모듈을 ${modules.size}개만 찾았다 — 파서가 고장났다.`);
    assert.ok(matrix.size >= 5, `선별기가 고를 수 있는 모듈이 ${matrix.size}개뿐이다 — 도출이 고장났다.`);
    assert.ok(modules.has('issue-tracking'), '알려진 모듈이 파싱되지 않았다.');
  });

  test('모든 Gradle 모듈이 CI 에서 실행된다', () => {
    const modules = gradleModules();
    const matrix = ciCoveredModules();

    const uncovered = [...modules].filter((m) => !matrix.has(m) && !SEPARATE_JOB_MODULES.has(m));

    assert.deepEqual(
      uncovered,
      [],
      `CI 에서 테스트가 안 도는 모듈: ${uncovered.join(', ')}\n` +
        `backend/modules/ 아래에 두어 선별기가 잡게 하거나, 별도 잡으로 돌린다면 ` +
        `이 테스트의 SEPARATE_JOB_MODULES 에 사유와 함께 등록하라.`,
    );
  });

  test('매트릭스에 존재하지 않는 모듈이 없다', () => {
    const modules = gradleModules();
    const matrix = ciCoveredModules();

    const phantom = [...matrix].filter((m) => !modules.has(m));

    assert.deepEqual(
      phantom,
      [],
      `Gradle 에 없는 모듈이 매트릭스에 있다: ${phantom.join(', ')}\n` +
        `모듈이 삭제·개명됐다면 매트릭스도 함께 고쳐야 한다 — 안 그러면 그 잡이 항상 실패한다.`,
    );
  });

  test('조립 부팅(app)은 별도 스테이지로 실행된다', () => {
    const src = fs.readFileSync(PIPELINE, 'utf8');

    // app 은 Testcontainers 를 관리하지 않고 외부 5433 postgres 를 쓰므로 서비스 컨테이너가 필요하다.
    assert.match(src, /:modules:app:test/, 'app 조립 부팅 잡이 없다 — 9 BC 를 한 컨텍스트에 올리는 검증이 빠진다.');
    // 비-prod 조립 가드는 태그로 기본 test 태스크에서 제외돼 있다. 이 스텝이 없으면 가드가 CI 에서
    // 0회 실행되고, 로컬 1회성 확인으로 끝나 썩는다(TODOS.md 의 pnpm test:workflow 미실행 사고와 동형).
    assert.match(
      src,
      /:modules:app:nonProdAssemblyTest/,
      '비-prod 조립 부팅 가드가 CI 에 배선되지 않았다 — 태그로 test 에서 제외돼 있어 아무 잡도 돌리지 않는다.',
    );
    assert.match(
      src,
      /pg16-pgmq/,
      'postgres 서비스 이미지가 pgmq 판이 아니다 — 일반 postgres:16 은 마이그레이션에서 실패한다.',
    );
    // ★단언을 **뒤집었다**(2026-09-09). Actions 시절에는 「포트가 55433 로 고정돼 있을 것」이
    //   요구였다. 러너가 1대라 고정이 안전했기 때문이다.
    //   젠킨스는 그 전제가 깨진다 — 두 빌드가 겹치면 뒤 빌드의 `docker rm -f` 가 앞 빌드
    //   DB 를 테스트 도중에 죽인다. `backend-ci.yml` assembly 잡 주석이 「러너를 늘리면 이
    //   조건이 성립한다」고 경고한 그 상태가 젠킨스에서는 **기본값**이다.
    //   그래서 이제는 **고정 포트가 결함**이고, 커널이 고르게 한 뒤 조회해야 한다.
    assert.match(
      src,
      /-p 0:5432/,
      'postgres 를 고정 포트로 띄운다 — 젠킨스는 빌드가 겹칠 수 있어 고정 포트가 서로의 DB 를 죽인다.',
    );
    assert.match(
      src,
      /docker port .*5432/,
      '동적 포트를 조회하지 않는다 — `-p 0:5432` 로 띄우고 실제 포트를 안 읽으면 붙을 수 없다.',
    );
    assert.doesNotMatch(
      src,
      /5433:5432|55433:5432/,
      '고정 포트 매핑이 남아 있다. 러너 1대 전제에서만 안전했던 설계다.',
    );
  });
});
