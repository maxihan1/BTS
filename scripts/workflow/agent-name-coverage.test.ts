// classify-task 가 지목하는 sub-agent 와 스킬이 dispatch 하는 sub-agent 가 실재하는지 강제한다
//
// ## 왜 이 파일이 있나
//
// `.claude/agents/*.md` 는 **어떤 기계도 읽지 않는 자산**이었다. CI 트리거에도 없었고,
// 이름을 들고 있는 곳은 산문 2곳뿐이었다(2026-08-13 실측). 그래서 에이전트 파일을 옮기거나
// 지워도 저장소 어디에서도 빨간불이 나지 않았다 — 실패는 **런타임에만** 드러난다.
// dispatch 대상이 없으면 Task 호출이 실패하고, 게이트 2 의 리뷰 2종 중 1종이 조용히 사라진다.
//
// ## 왜 단방향인가
//
// `AgentName` ⊆ 실재 파일. 반대 방향(파일 ⊆ 유니온)은 강제하지 않는다.
// `code-reviewer` 처럼 **classify 의 dispatch 대상이 아니면서** 스킬이 고정 호출하는
// 에이전트가 있기 때문이다. 양방향으로 묶으면 그런 파일을 지우거나 유니온을 오염시키는
// 둘 중 하나를 강요하게 된다.
//
// ## 앵커를 「지금 있는 값」으로 잡지 않는다
//
// 사라질 수 있는 이름을 존재 단언으로 박으면 판별식이 **설계 변경 자체를 막는다.**
// 그래서 하한(개수)과 이 저장소에서 없어질 수 없는 기본값(`backend-engineer`) 만 앵커로 쓴다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const TYPES_FILE = path.join(REPO_ROOT, 'scripts/workflow/types.ts');
const AGENTS_DIR = path.join(REPO_ROOT, '.claude/agents');
const CODEREVIEW_SKILL = path.join(REPO_ROOT, '.claude/skills/bts-codereview/SKILL.md');

/**
 * Claude Code 가 기본 제공하는 dispatch 대상. 저장소 파일이 없어도 실재한다.
 *
 * 여기 적힌 것만 예외다 — 플러그인 네임스페이스(`foo:bar`)는 넣지 않는다.
 * 플러그인은 프로젝트 설정으로 꺼질 수 있고, 꺼지면 그 dispatch 는 조용히 실패한다.
 */
const BUILTIN_AGENTS = new Set(['general-purpose']);

/**
 * `types.ts` 의 `AgentName` 유니온에서 값을 뽑는다.
 *
 * 타입을 import 해도 런타임에는 값이 없다(TS 타입은 지워진다). 별도 배열 상수를 두면
 * 그 배열과 유니온이 또 어긋나므로 원본을 읽는다 — `skill-type-coverage` 와 같은 방식이다.
 */
function parseAgentNames(): string[] {
  const src = fs.readFileSync(TYPES_FILE, 'utf8');
  const m = src.match(/export type AgentName =([\s\S]*?);/);
  assert.ok(m, 'types.ts 에서 AgentName 유니온을 찾지 못했다 — 선언 서식이 바뀌었다.');
  return [...(m[1] as string).matchAll(/'([a-z-]+)'/g)].map((x) => x[1] as string);
}

/** `.claude/agents/*.md` 의 frontmatter `name` 값 */
function parseAgentFiles(): { file: string; name: string | null }[] {
  return fs
    .readdirSync(AGENTS_DIR)
    .filter((f) => f.endsWith('.md'))
    .sort()
    .map((file) => {
      const src = fs.readFileSync(path.join(AGENTS_DIR, file), 'utf8');
      const m = src.match(/^---\r?\n([\s\S]*?)\r?\n---/);
      const name = m?.[1]?.match(/^name:\s*(\S+)\s*$/m)?.[1] ?? null;
      return { file, name };
    });
}

/** 스킬 본문이 dispatch 하는 `subagent_type` 값 전부 */
function parseDispatchTargets(file: string): string[] {
  const src = fs.readFileSync(file, 'utf8');
  return [...src.matchAll(/subagent_type\s*:\s*["']([^"']+)["']/g)].map((m) => m[1] as string);
}

describe('sub-agent 이름 정합', () => {
  test('입력 두 벌을 실제로 읽었다 (비-공허 짝)', () => {
    const union = parseAgentNames();
    const files = parseAgentFiles();
    // 파싱이 0건을 내면 아래 차집합이 공허하게 통과한다. 하한은 「지금 개수」가 아니라
    // 「이보다 적으면 파서 고장」인 값으로 잡는다 — 에이전트를 줄이는 설계 변경을 막지 않는다.
    assert.ok(union.length >= 4, `AgentName 이 ${union.length}종뿐이다 — 파싱이 고장났을 수 있다.`);
    assert.ok(
      union.includes('backend-engineer'),
      'backend-engineer 가 AgentName 에 없다 — 이 저장소의 dispatch 기본값이 사라졌다.',
    );
    assert.ok(files.length >= 5, `.claude/agents/*.md 가 ${files.length}개뿐이다 — 디렉터리를 잘못 읽었다.`);
  });

  test('frontmatter name 이 없는 에이전트 파일이 없다', () => {
    const nameless = parseAgentFiles().filter((f) => f.name === null).map((f) => f.file);
    assert.deepEqual(
      nameless,
      [],
      `frontmatter 에 name 이 없다: ${nameless.join(', ')}\n` +
        'CLI 가 읽는 식별자는 파일명이 아니라 name 이다 — 없으면 그 에이전트는 호출되지 않는다.',
    );
  });

  test('AgentName 이 전부 실재 파일을 가리킨다 (단방향 차집합 0)', () => {
    const declared = new Set(parseAgentFiles().map((f) => f.name).filter((n): n is string => n !== null));
    const missing = parseAgentNames().filter((name) => !declared.has(name));
    assert.deepEqual(
      missing,
      [],
      `classify-task 가 지목하는데 파일이 없는 에이전트: ${missing.join(', ')}\n` +
        '이 상태는 dispatch 시점에야 드러난다 — 그때는 이미 작업이 끊긴 뒤다.',
    );
  });

  test('bts-codereview 가 dispatch 하는 에이전트가 실재한다', () => {
    const targets = parseDispatchTargets(CODEREVIEW_SKILL);
    assert.ok(targets.length > 0, `${CODEREVIEW_SKILL} 에서 subagent_type 을 하나도 못 뽑았다 — 아래 대조가 공허하다.`);

    const declared = new Set(parseAgentFiles().map((f) => f.name).filter((n): n is string => n !== null));
    const missing = targets.filter((t) => !declared.has(t) && !BUILTIN_AGENTS.has(t));

    assert.deepEqual(
      missing,
      [],
      `bts-codereview 가 없는 에이전트를 부른다: ${missing.join(', ')}\n\n` +
        '저장소로 이관하기 전 이름(플러그인 네임스페이스 포함)이 남아 있으면, 플러그인이 꺼진 환경에서 ' +
        '리뷰 1종이 조용히 사라진다 — 게이트 2 의 검출력이 절반이 된다.',
    );
  });
});
