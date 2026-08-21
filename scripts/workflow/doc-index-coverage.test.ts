// 문서 인덱스 판별식 — 재생성 diff(I) · 고아(J) · 깨진 링크(K) · SOURCES⟺CI paths(L)
// 실행. node --experimental-strip-types --test scripts/workflow/doc-index-coverage.test.ts
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

test('룰 I — 현재 인덱스가 재생성 결과와 같다 (--check, 파일을 쓰지 않는다)', () => {
  // ★ 여기서 생성기를 **쓰기 모드**로 돌리면 안 된다. 재생성이 검사 대상인 손 수정을
  //   덮어써서 판별식이 스스로 증거를 지운다 — 뮤테이션에서 이 테스트뿐 아니라
  //   뒤따르는 룰 J·K 까지 전부 green 이 됐다(1차 주입 실측). --check 는 비교만 한다.
  let failed = false;
  let out = '';
  try {
    out = execFileSync('node', ['scripts/build-doc-index.mjs', '--check'], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
      stdio: 'pipe',
    });
  } catch (e) {
    failed = true;
    out = `${(e as { stdout?: string }).stdout ?? ''}${(e as { stderr?: string }).stderr ?? ''}`;
  }
  assert.equal(failed, false, `인덱스 drift.\n${out}`);
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
// ★2026-08-21 — 「내 입력이 CI 트리거 paths 에 있는가」 단언을 여기서 지웠다.
//   CI 자동 실행을 껐고, 판별식은 이제 `.husky/pre-push` 가 **조건 없이 전량** 돌린다.
//   그 무조건성은 `scripts/workflow/discriminant-hook-wiring.test.ts` 가 강제한다.
//   경로 짝맞춤 목록이 필요 없어졌으므로 보장은 유지되고 유지비만 사라진다.
