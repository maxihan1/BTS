// backend-ci 매트릭스가 Gradle 모듈 전량을 덮는지 강제하는 정합 테스트
//
// 왜 이 테스트가 있나. `.github/workflows/backend-ci.yml` 의 매트릭스는 **하드코딩 목록**이고,
// Gradle 모듈은 `backend/settings.gradle.kts` 에서 자란다. 둘의 정합을 아무도 보지 않으면
// **새 BC 모듈이 CI 에서 조용히 빠진다** — 이 저장소가 겪은 실패 양식 그대로다
// (CORS allowedMethods ↔ 컨트롤러 매핑, BC_KEYWORDS ↔ 도메인 어휘, 스킬 분기표 ↔ TaskType).
//
// 판별식. 두 목록의 **차집합이 0** 인지 본다. 새 모듈을 추가하면 이 테스트가 먼저 깨진다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const SETTINGS = path.join(REPO_ROOT, 'backend/settings.gradle.kts');
const BACKEND_CI = path.join(REPO_ROOT, '.github/workflows/backend-ci.yml');

/** `include(":modules:xxx")` 에서 모듈명을 뽑는다 */
function gradleModules(): Set<string> {
  const src = fs.readFileSync(SETTINGS, 'utf8');
  return new Set([...src.matchAll(/include\(":modules:([a-z-]+)"\)/g)].map((m) => m[1] as string));
}

/** backend-ci 매트릭스의 모듈 목록 */
function ciMatrixModules(): Set<string> {
  const src = fs.readFileSync(BACKEND_CI, 'utf8');
  return new Set([...src.matchAll(/^ {10}- ([a-z-]+)$/gm)].map((m) => m[1] as string));
}

/** 매트릭스 밖에서 별도 잡으로 도는 모듈 (조립 부팅은 서비스 컨테이너가 필요해 분리돼 있다) */
const SEPARATE_JOB_MODULES = new Set(['app']);

describe('backend-ci 매트릭스 ↔ Gradle 모듈 정합', () => {
  test('판별식이 비어 있지 않다 (양성 대조군)', () => {
    const modules = gradleModules();
    const matrix = ciMatrixModules();

    // 하한이 없으면 파서가 0건을 내도 아래 차집합이 공허하게 통과한다.
    // 0 이라는 결과는 "없다" 가 아니라 "내 판별식이 틀렸다" 를 먼저 의심해야 한다.
    assert.ok(modules.size >= 5, `settings.gradle.kts 에서 모듈을 ${modules.size}개만 찾았다 — 파서가 고장났다.`);
    assert.ok(matrix.size >= 5, `backend-ci 매트릭스에서 ${matrix.size}개만 찾았다 — 파서가 고장났다.`);
    assert.ok(modules.has('issue-tracking'), '알려진 모듈이 파싱되지 않았다.');
  });

  test('모든 Gradle 모듈이 CI 에서 실행된다', () => {
    const modules = gradleModules();
    const matrix = ciMatrixModules();

    const uncovered = [...modules].filter((m) => !matrix.has(m) && !SEPARATE_JOB_MODULES.has(m));

    assert.deepEqual(
      uncovered,
      [],
      `CI 에서 테스트가 안 도는 모듈: ${uncovered.join(', ')}\n` +
        `backend-ci.yml 의 matrix.module 에 추가하거나, 별도 잡으로 돌린다면 ` +
        `이 테스트의 SEPARATE_JOB_MODULES 에 사유와 함께 등록하라.`,
    );
  });

  test('매트릭스에 존재하지 않는 모듈이 없다', () => {
    const modules = gradleModules();
    const matrix = ciMatrixModules();

    const phantom = [...matrix].filter((m) => !modules.has(m));

    assert.deepEqual(
      phantom,
      [],
      `Gradle 에 없는 모듈이 매트릭스에 있다: ${phantom.join(', ')}\n` +
        `모듈이 삭제·개명됐다면 매트릭스도 함께 고쳐야 한다 — 안 그러면 그 잡이 항상 실패한다.`,
    );
  });

  test('조립 부팅(app)은 별도 잡으로 실행된다', () => {
    const src = fs.readFileSync(BACKEND_CI, 'utf8');

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
    assert.match(src, /5433:5432/, 'postgres 포트가 5433 이 아니다 — application.yml 기본값과 어긋나 못 붙는다.');
  });
});
