// jOOQ 코드 생성용 미러 SQL 이 Flyway 마이그레이션과 같은 테이블·컬럼 집합을 갖는지 강제한다
//
// ## 왜 있나
//
// jOOQ 코드 생성은 **Flyway 마이그레이션을 읽지 않는다.** `build.gradle.kts` 가
// `TC_INITSCRIPT=file:src/main/resources/db/codegen/init_codegen.sql` 로 **손관리 미러**를
// 컨테이너에 적용하고 그것을 introspection 한다. 미러가 낡으면 `BOARDS.BOARD_TYPE` 같은 상수가
// 생성되지 않아 컴파일이 깨진다 — 또는 더 나쁘게, 지운 컬럼이 상수로 남는다.
//
// 지금까지 그 정합을 지키는 장치는 미러 파일 **머리의 자연어 주석 3줄**뿐이었다
// (*"... 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성)"*).
// `docs/rules/behavior-rules.md §3` 이 **「자연어 지시는 강제가 아니다」** 를 이미 판정했고,
// 2026-09-01 FR-BD-04 계획 리뷰에서 실제로 이 양식에 걸릴 뻔했다(지적 E2).
// 지배 결함 양식 `two-lists-never-check-each-other` 의 처방 그대로 **차집합**을 센다.
//
// ## 무엇을 강제하나
//
// ① 미러가 있는 BC 마다, **마이그레이션이 만든 (테이블, 컬럼) 집합 == 미러의 집합**.
//    양방향 차집합을 둘 다 본다 — 누락만 보면 「지운 컬럼이 미러에 남는」 반대 방향이 썩는다.
// ② **파싱하지 못한 DDL 은 통과가 아니라 실패**로 떨어뜨린다. 파서가 모르는 문법을 만나면
//    조용히 0건이 되어 단언이 공허해진다(`partial-column-parser-lets-unread-column-rot`).
// ③ 판정 함수를 **픽스처로 직접 흔든다**(비-공허 짝). 미러에서 컬럼 하나를 빼면 반드시 잡힌다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const MODULES_DIR = path.join(REPO_ROOT, 'backend/modules');

/** 미러 파일의 저장소 내 상대 경로 규약. 이 이름을 가진 파일이 있는 BC 만 검사 대상이다. */
const MIRROR_RELATIVE = 'src/main/resources/db/codegen/init_codegen.sql';
/** 마이그레이션 루트의 상대 경로 규약. */
const MIGRATION_RELATIVE = 'src/main/resources/db/migration';

/** `(테이블, 컬럼)` 집합. 비교 단위다. */
type ColumnSet = Set<string>;

/** SQL 주석과 문자열 리터럴을 지운다 — 주석 안의 `CREATE TABLE` 예시가 파서를 속이지 못하게. */
function stripNoise(sql: string): string {
  return sql
    .replace(/--[^\n]*/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/'(?:[^']|'')*'/g, "''");
}

/**
 * `CREATE TABLE t (...)` 본문에서 컬럼명을 뽑는다.
 *
 * 괄호 깊이를 세어 본문을 정확히 자르고, 최상위 콤마로만 쪼갠다 —
 * `CHECK (x IN ('A','B'))` 안의 콤마에 속지 않기 위함이다.
 */
function parseCreateTable(sql: string, into: ColumnSet, unparsed: string[]): void {
  const re = /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(sql)) !== null) {
    const table = m[1].toLowerCase();
    let depth = 1;
    let i = re.lastIndex;
    for (; i < sql.length && depth > 0; i += 1) {
      if (sql[i] === '(') depth += 1;
      else if (sql[i] === ')') depth -= 1;
    }
    if (depth !== 0) {
      unparsed.push(`CREATE TABLE ${table} — 괄호가 닫히지 않았다`);
      continue;
    }
    const body = sql.slice(re.lastIndex, i - 1);

    // 최상위 콤마 분할
    const parts: string[] = [];
    let d = 0;
    let cur = '';
    for (const ch of body) {
      if (ch === '(') d += 1;
      if (ch === ')') d -= 1;
      if (ch === ',' && d === 0) {
        parts.push(cur);
        cur = '';
      } else {
        cur += ch;
      }
    }
    parts.push(cur);

    for (const raw of parts) {
      const part = raw.trim();
      if (part.length === 0) continue;
      // 테이블 제약은 컬럼이 아니다
      if (/^(PRIMARY|UNIQUE|CHECK|FOREIGN|CONSTRAINT|EXCLUDE|LIKE)\b/i.test(part)) continue;
      const col = /^([A-Za-z_][A-Za-z0-9_]*)\s/.exec(part);
      if (col === null) {
        unparsed.push(`${table} — 컬럼 정의를 읽지 못했다: ${part.slice(0, 40)}`);
        continue;
      }
      into.add(`${table}.${col[1].toLowerCase()}`);
    }
  }
}

/** `ALTER TABLE t ADD COLUMN c` / `DROP COLUMN c` 를 반영한다. */
function applyAlters(sql: string, into: ColumnSet, unparsed: string[]): void {
  const re = /ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)\s+([\s\S]*?);/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(sql)) !== null) {
    const table = m[1].toLowerCase();
    const body = m[2];
    const add = /\bADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)/gi;
    const drop = /\bDROP\s+COLUMN\s+(?:IF\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)/gi;
    // RENAME COLUMN 은 **집합을 바꾼다** — 무시하면 이름이 바뀐 컬럼이 양쪽에서 어긋난 채 통과한다.
    const rename = /\bRENAME\s+COLUMN\s+([A-Za-z_][A-Za-z0-9_]*)\s+TO\s+([A-Za-z_][A-Za-z0-9_]*)/gi;
    let a: RegExpExecArray | null;
    let touched = false;
    while ((a = add.exec(body)) !== null) {
      into.add(`${table}.${a[1].toLowerCase()}`);
      touched = true;
    }
    while ((a = drop.exec(body)) !== null) {
      into.delete(`${table}.${a[1].toLowerCase()}`);
      touched = true;
    }
    while ((a = rename.exec(body)) !== null) {
      into.delete(`${table}.${a[1].toLowerCase()}`);
      into.add(`${table}.${a[2].toLowerCase()}`);
      touched = true;
    }
    // 컬럼 집합을 **바꾸지 않는** ALTER 형태들. 여기 없는 형태는 통과시키지 않고 실패로 올린다 —
    // 모르는 문법을 「없는 것」으로 세면 판정이 조용히 공허해진다.
    const NO_OP_ALTER =
      /\b(ALTER\s+COLUMN|RENAME\s+TO|RENAME\s+CONSTRAINT|ADD\s+(PRIMARY\s+KEY|UNIQUE|FOREIGN\s+KEY|CHECK|EXCLUDE|CONSTRAINT)|DROP\s+(CONSTRAINT|DEFAULT|NOT\s+NULL)|SET\s+(DEFAULT|NOT\s+NULL|SCHEMA|TABLESPACE|LOGGED|UNLOGGED|\()|VALIDATE\s+CONSTRAINT|OWNER\s+TO|ENABLE|DISABLE|CLUSTER|INHERIT|OF|NO\s+INHERIT)\b/i;
    if (!touched && !NO_OP_ALTER.test(body)) {
      unparsed.push(`${table} — ALTER 를 읽지 못했다: ${body.trim().slice(0, 40)}`);
    }
  }
}

/** 한 SQL 문자열에서 (테이블, 컬럼) 집합을 만든다. */
export function columnsOf(sql: string): { columns: ColumnSet; unparsed: string[] } {
  const clean = stripNoise(sql);
  const columns: ColumnSet = new Set();
  const unparsed: string[] = [];
  parseCreateTable(clean, columns, unparsed);
  applyAlters(clean, columns, unparsed);
  return { columns, unparsed };
}

/** 미러를 가진 BC 목록. 없으면 검사 대상이 0건이므로 그 사실 자체를 실패로 만든다. */
function bcsWithMirror(): string[] {
  if (!fs.existsSync(MODULES_DIR)) return [];
  return fs
    .readdirSync(MODULES_DIR)
    .filter((bc) => fs.existsSync(path.join(MODULES_DIR, bc, MIRROR_RELATIVE)))
    .sort();
}

/** 그 BC 의 마이그레이션 SQL 을 V 번호 순으로 이어 붙인다. */
function migrationSql(bc: string): string {
  const root = path.join(MODULES_DIR, bc, MIGRATION_RELATIVE);
  if (!fs.existsSync(root)) return '';
  const files: string[] = [];
  const walk = (dir: string): void => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.sql')) files.push(p);
    }
  };
  walk(root);
  files.sort((a, b) => path.basename(a).localeCompare(path.basename(b), 'en', { numeric: true }));
  return files.map((f) => fs.readFileSync(f, 'utf8')).join('\n');
}

describe('jOOQ 미러 ↔ 마이그레이션 정합', () => {
  const bcs = bcsWithMirror();

  test('미러를 가진 BC 가 하나 이상이다 (비-공허 하한)', () => {
    assert.ok(
      bcs.length > 0,
      `미러 파일(${MIRROR_RELATIVE})을 가진 BC 가 0건이다 — 경로 규약이 바뀌었다면 이 판별식도 같이 고쳐라. ` +
        '0건이면 아래 단언이 전부 공허하게 통과한다.',
    );
  });

  for (const bc of bcs) {
    test(`${bc} — 미러와 마이그레이션의 컬럼 집합이 같다`, () => {
      const mirror = columnsOf(fs.readFileSync(path.join(MODULES_DIR, bc, MIRROR_RELATIVE), 'utf8'));
      const migration = columnsOf(migrationSql(bc));

      assert.deepStrictEqual(
        [...mirror.unparsed, ...migration.unparsed],
        [],
        `${bc} — 파싱하지 못한 DDL 이 있다. 통과가 아니라 실패로 떨어뜨린다 ` +
          '(읽지 못한 것을 없는 것으로 세면 판정이 조용히 공허해진다).',
      );

      // ★ 비교 범위 = **미러가 올린 테이블**뿐이다. 미러를 마이그레이션의 전량 사본으로 요구하지 않는다 —
      // jOOQ 생성 상수를 안 쓰고 `table(name("x"))` 로 런타임 구성하는 저장소가 실재한다
      // (`WorkflowSchemeRepository.kt:59`). 그런 테이블은 미러에 없는 것이 정상이다.
      //
      // 대신 **올렸으면 완전해야 한다** — 이미 미러에 있는 테이블에 컬럼이 하나 늘었는데 미러가
      // 안 따라오는 것이 실제 위험이고(2026-09-01 지적 E2), 새 테이블 통째 누락은 상수 부재로
      // 컴파일이 즉시 깨져 시끄럽게 드러난다.
      const mirroredTables = new Set([...mirror.columns].map((c) => c.split('.')[0]));
      const missingInMirror = [...migration.columns]
        .filter((c) => mirroredTables.has(c.split('.')[0]) && !mirror.columns.has(c))
        .sort();
      const staleInMirror = [...mirror.columns].filter((c) => !migration.columns.has(c)).sort();

      assert.deepStrictEqual(
        { missingInMirror, staleInMirror },
        { missingInMirror: [], staleInMirror: [] },
        `${bc} — jOOQ 미러(${MIRROR_RELATIVE})가 마이그레이션과 어긋난다.\n` +
          `  미러에 없음(=jOOQ 상수 미생성): ${missingInMirror.join(', ') || '없음'}\n` +
          `  미러에만 있음(=지운 컬럼이 상수로 남음): ${staleInMirror.join(', ') || '없음'}\n` +
          '  → 마이그레이션을 추가·수정했으면 같은 커밋에서 미러도 고쳐라.',
      );
    });
  }

  // ── 비-공허 짝 — 판정 함수를 픽스처로 직접 흔든다 ──────────────────────────────
  test('★미러에서 컬럼을 빼면 잡는다 (비-공허 짝)', () => {
    const migration = columnsOf('CREATE TABLE t (a UUID PRIMARY KEY, b TEXT NOT NULL);');
    const mirror = columnsOf('CREATE TABLE t (a UUID PRIMARY KEY);');
    const missing = [...migration.columns].filter((c) => !mirror.columns.has(c));
    assert.deepStrictEqual(missing, ['t.b']);
  });

  test('★미러에만 남은 컬럼도 잡는다 (반대 방향 비-공허 짝)', () => {
    const migration = columnsOf('CREATE TABLE t (a UUID PRIMARY KEY);');
    const mirror = columnsOf('CREATE TABLE t (a UUID PRIMARY KEY, gone TEXT);');
    const stale = [...mirror.columns].filter((c) => !migration.columns.has(c));
    assert.deepStrictEqual(stale, ['t.gone']);
  });

  test('★ALTER ADD COLUMN 이 집합에 반영된다', () => {
    const { columns } = columnsOf(
      'CREATE TABLE t (a UUID PRIMARY KEY);\nALTER TABLE t ADD COLUMN b VARCHAR(16) NOT NULL DEFAULT \'X\';',
    );
    assert.ok(columns.has('t.b'), 'ALTER 로 더한 컬럼이 집합에 없다 — 파서가 ALTER 를 놓치면 신규 마이그레이션이 통째로 안 보인다');
  });

  test('★테이블 제약을 컬럼으로 세지 않는다', () => {
    const { columns } = columnsOf(
      "CREATE TABLE t (a UUID, b TEXT, CONSTRAINT t_uq UNIQUE (a, b), CHECK (b IN ('X','Y')));",
    );
    assert.deepStrictEqual([...columns].sort(), ['t.a', 't.b']);
  });

  test('★미러에 올리지 않은 테이블은 요구하지 않는다 (범위 계약)', () => {
    const migration = columnsOf('CREATE TABLE kept (a UUID);\nCREATE TABLE runtime_only (b UUID);');
    const mirror = columnsOf('CREATE TABLE kept (a UUID);');
    const mirrored = new Set([...mirror.columns].map((c) => c.split('.')[0]));
    const missing = [...migration.columns].filter(
      (c) => mirrored.has(c.split('.')[0]) && !mirror.columns.has(c),
    );
    assert.deepStrictEqual(missing, [], '미러에 없는 테이블까지 요구하면 런타임 구성 저장소가 부당하게 막힌다');
  });

  test('★올린 테이블에 컬럼이 늘면 잡는다 (E2 재현)', () => {
    const migration = columnsOf(
      'CREATE TABLE boards (id UUID);\nALTER TABLE boards ADD COLUMN board_type VARCHAR(16) NOT NULL DEFAULT \'KANBAN\';',
    );
    const mirror = columnsOf('CREATE TABLE boards (id UUID);');
    const mirrored = new Set([...mirror.columns].map((c) => c.split('.')[0]));
    const missing = [...migration.columns].filter(
      (c) => mirrored.has(c.split('.')[0]) && !mirror.columns.has(c),
    );
    assert.deepStrictEqual(missing, ['boards.board_type']);
  });

  test('★RENAME COLUMN 이 집합에 반영된다 (무시하면 조용히 어긋난다)', () => {
    const { columns } = columnsOf(
      'CREATE TABLE t (old_name TEXT);\nALTER TABLE t RENAME COLUMN old_name TO new_name;',
    );
    assert.deepStrictEqual([...columns], ['t.new_name']);
  });

  test('★주석 안의 DDL 에 속지 않는다', () => {
    const { columns } = columnsOf('-- CREATE TABLE ghost (x TEXT);\nCREATE TABLE t (a UUID);');
    assert.deepStrictEqual([...columns], ['t.a']);
  });
});
