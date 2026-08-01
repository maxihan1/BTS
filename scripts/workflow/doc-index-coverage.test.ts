// 문서 인덱스 판별식 — 재생성 diff(I) · 고아(J) · 깨진 링크(K) · SOURCES⟺CI paths(L)
// 실행. node --test scripts/workflow/doc-index-coverage.test.ts
//
// 왜 판별식인가. 이 저장소의 지배 결함은 "두 하드코딩 목록이 서로를 안 봐서 조용히 갈라지는" 것이다.
// 인덱스와 실제 파일이 정확히 그 두 목록이다. 차집합을 기계가 보게 만든다.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const CI_FILE = path.join(REPO_ROOT, '.github/workflows/workflow-scripts-ci.yml');
const INDEX_FILES = ['docs/INDEX.md', 'docs/INDEX-fr.md', 'docs/INDEX-recent.md'];

test('룰 I — 인덱스를 재생성해도 git diff 가 클린이다', () => {
  execFileSync('node', ['scripts/build-doc-index.mjs'], { cwd: REPO_ROOT, stdio: 'pipe' });
  const diff = execFileSync('git', ['diff', '--name-only', '--', ...INDEX_FILES], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
  }).trim();
  assert.equal(
    diff,
    '',
    `인덱스 drift. 재생성 결과가 커밋본과 다르다:\n${diff}\n` +
      '→ node scripts/build-doc-index.mjs 후 커밋할 것.',
  );
});

test('룰 J — docs 문서 전량이 시간축 인덱스에 등록되어 있다', () => {
  const recent = fs.readFileSync(path.join(REPO_ROOT, 'docs/INDEX-recent.md'), 'utf8');
  const orphans: string[] = [];
  for (const dir of ['docs/plans', 'docs/specs', 'docs/decisions', 'docs/adr']) {
    for (const f of fs.readdirSync(path.join(REPO_ROOT, dir)).filter((x) => x.endsWith('.md'))) {
      const slug = f.replace(/^\d{4}-\d{2}-\d{2}-/, '').replace(/\.md$/, '');
      if (!recent.includes(`| ${slug} |`)) orphans.push(`${dir}/${f}`);
    }
  }
  assert.deepEqual(orphans, [], `인덱스 미등록 문서 ${orphans.length}건`);
});

test('룰 J-역 — 시간축 인덱스의 slug 가 전부 실재한다 (삭제 방향)', () => {
  // 봉인은 기본적으로 절반만 닫힌다. 추가 방향만 막으면 삭제된 문서가 인덱스에 남는다.
  const recent = fs.readFileSync(path.join(REPO_ROOT, 'docs/INDEX-recent.md'), 'utf8');
  const actual = new Set<string>();
  for (const dir of ['docs/plans', 'docs/specs', 'docs/decisions', 'docs/adr']) {
    for (const f of fs.readdirSync(path.join(REPO_ROOT, dir)).filter((x) => x.endsWith('.md'))) {
      actual.add(f.replace(/^\d{4}-\d{2}-\d{2}-/, '').replace(/\.md$/, ''));
    }
  }
  const stale: string[] = [];
  for (const line of recent.split('\n')) {
    const m = line.match(/^\| \d{4}-\d{2}-\d{2} \| ([^|]+) \|/);
    if (m && !actual.has(m[1].trim())) stale.push(m[1].trim());
  }
  assert.deepEqual(stale, [], `실재하지 않는 문서가 인덱스에 ${stale.length}건`);
});

test('룰 K — 인덱스가 가리키는 파일이 전부 실재한다', () => {
  const broken: string[] = [];
  for (const idx of INDEX_FILES) {
    const body = fs.readFileSync(path.join(REPO_ROOT, idx), 'utf8');
    for (const m of body.matchAll(/\]\(\/(docs\/[^)]+\.md)\)/g)) {
      if (!fs.existsSync(path.join(REPO_ROOT, m[1]))) broken.push(`${idx} → ${m[1]}`);
    }
  }
  assert.deepEqual(broken, [], `깨진 링크 ${broken.length}건`);
});

test('룰 L — 생성기 SOURCES 가 전부 CI 트리거 paths 에 있다 (pull_request·push 양쪽)', async () => {
  const { SOURCES } = await import('../doc-index/config.mjs');
  const ci = fs.readFileSync(CI_FILE, 'utf8');
  // pull_request 블록과 push 블록을 갈라 각각 검사한다. 한쪽만 걸면 절반 봉인이다 —
  // PR 에서는 돌고 main 직접 push 에서는 안 도는 상태가 된다.
  const prBlock = ci.slice(ci.indexOf('pull_request:'), ci.indexOf('push:'));
  const pushBlock = ci.slice(ci.indexOf('push:'), ci.indexOf('concurrency:'));
  const missing: string[] = [];
  for (const src of SOURCES.filter((s: { repoRelative: boolean }) => s.repoRelative)) {
    for (const [name, block] of [
      ['pull_request', prBlock],
      ['push', pushBlock],
    ] as const) {
      const covered = block.includes(`'${src.dir}/**'`) || block.includes(`'docs/**'`);
      if (!covered) missing.push(`${src.dir} (${name})`);
    }
  }
  assert.deepEqual(
    missing,
    [],
    `CI 트리거 미포함 ${missing.length}건 — 이 입력만 바꾸는 PR 에서 판별식이 0회 실행된다`,
  );
});

test('룰 L-2 — 생성기 자신의 경로도 CI 트리거에 있다', () => {
  // 생성기를 고치면 인덱스 결과가 바뀐다. 생성기 경로가 트리거에 없으면
  // 생성기만 고치는 PR 에서 룰 I 가 0회 실행되고 통과한다.
  const ci = fs.readFileSync(CI_FILE, 'utf8');
  const prBlock = ci.slice(ci.indexOf('pull_request:'), ci.indexOf('push:'));
  const pushBlock = ci.slice(ci.indexOf('push:'), ci.indexOf('concurrency:'));
  for (const [name, block] of [
    ['pull_request', prBlock],
    ['push', pushBlock],
  ] as const) {
    assert.ok(
      block.includes("'scripts/doc-index/**'"),
      `scripts/doc-index/** 가 ${name} paths 에 없다`,
    );
  }
});
