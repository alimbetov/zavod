import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const catalog=JSON.parse(readFileSync(new URL('../../../docs/design/ux-99-variants.json',import.meta.url),'utf8'));
test('exactly 99 distinct role/layout/lens variations',()=>{
  assert.equal(catalog.variants.length,99);
  assert.equal(new Set(catalog.variants.map(x=>x.role+'|'+x.layout+'|'+x.lens)).size,99);
  assert.equal(catalog.variants[0].id,'ZV-001');
  assert.equal(catalog.variants[98].id,'ZV-099');
});
test('role-specific defaults are included',()=>{
  const combos=new Set(catalog.variants.map(x=>x.role+'|'+x.layout+'|'+x.lens));
  for(const key of ['ENGINEER|SPLIT|TOPOLOGY','OPERATOR|OVERVIEW|QUALITY',
    'REVIEWER|SPLIT|CHANGE'])assert.ok(combos.has(key),key);
});
test('a current production-state claim is never hard-coded in the workspace',()=>{
  const source=readFileSync(new URL('../src/workspace.tsx',import.meta.url),'utf8');
  assert.match(source,/UNKNOWN_NO_TELEMETRY/);
  assert.doesNotMatch(source,/live\s*[:=]\s*true/);
});
