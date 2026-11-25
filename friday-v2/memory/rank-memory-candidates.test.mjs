import test from 'node:test';
import assert from 'node:assert/strict';
import { rankMemoryCandidates as rank } from './rank-memory-candidates.mjs';
const row=(id,extra={})=>({id,owner_id:"owner",kind:"memory",search_consent:true,sensitivity:"standard",deleted_at:null,created_at:"2026-11-25T00:00:00Z",...extra});
const ids=(opts)=>rank({ownerId:'owner',...opts}).map(x=>x.id);
test('hybrid overlap promotes the shared result and deduplicates',()=>{
  const results=rank({ownerId:'owner',lexical:[row('a'),row('b'),row('b')],semantic:[row('b',{similarity:.9})]});
  assert.equal(results[0].id,'b');
  assert.deepEqual(results[0].sources,['lexical','semantic']);
  assert.equal(results.length,2);
});
test('owner and consent isolation',()=>assert.deepEqual(ids({lexical:[row('foreign',{owner_id:'other'}),row('no',{search_consent:false}),row('ok')]}),['ok']));
test('sensitive, transactional and deleted records excluded',()=>assert.deepEqual(ids({lexical:[row('health',{sensitivity:'private'}),row('payment',{kind:'payment'}),row('deleted',{deleted_at:'2026-11-25'}),row('ok')]}),['ok']));
test('semantic threshold and keyword fallback',()=>assert.deepEqual(ids({lexical:[row('keyword')],semantic:[row('weak',{similarity:.2})]}),['keyword']));
test('category and date filters',()=>assert.deepEqual(ids({category:'office',from:'2026-11-25T00:00:00Z',lexical:[row('old',{category:'office',created_at:'2026-11-24'}),row('personal',{category:'personal'}),row('ok',{category:'office'})]}),['ok']));
test('missing authenticated owner fails closed',()=>assert.throws(()=>rank({ownerId:''}),/owner/));
