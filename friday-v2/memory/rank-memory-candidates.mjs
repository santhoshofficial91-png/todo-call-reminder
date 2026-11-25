// Friday v2: pure server-side candidate fusion, not a database or embedding provider.
// Caller MUST authenticate the owner and apply owner, consent, sensitivity, deletion,
// category and time filters in SQL BEFORE fetching candidates or embedding text.
// Input arrays are ranked best-first by lexical relevance and vector similarity.
// Only opted-in memory text is eligible. Never call this for transactional data.
// Fetch returned IDs through the same authorized owner-scoped query; never trust a client ownerId.
// Similarity threshold is provisional and must be calibrated with Tamil/English test data.
export function rankMemoryCandidates({ownerId, lexical=[], semantic=[], category=null, from=null, to=null, limit=5, minSimilarity=0.75}){
  if(typeof ownerId!=="string"||!ownerId.trim())throw new Error("Authenticated owner required");
  if(!Number.isInteger(limit)||limit<1||limit>20)throw new Error("Invalid limit");
  if(!Number.isFinite(minSimilarity)||minSimilarity<0||minSimilarity>1)throw new Error("Invalid similarity threshold");
  const date=(value)=>{if(value===null)return null;const ms=Date.parse(value);if(!Number.isFinite(ms))throw new Error("Invalid date filter");return ms};
  const start=date(from),end=date(to);if(start!==null&&end!==null&&start>end)throw new Error("Invalid date range");
  const kinds=new Set(["memory","note","diary","decision","creator_idea"]);
  const eligible=(r)=>{const t=Date.parse(r?.created_at);return r&&typeof r.id==="string"&&r.id.length>0&&r.owner_id===ownerId&&r.search_consent===true&&r.sensitivity==="standard"&&r.deleted_at===null&&kinds.has(r.kind)&&(!category||r.category===category)&&Number.isFinite(t)&&(start===null||t>=start)&&(end===null||t<=end)};
  const byId=new Map();
  const add=(rows,source)=>{const seen=new Set();let rank=0;for(const r of rows){if(!eligible(r)||seen.has(r.id)||(source==="semantic"&&(!Number.isFinite(r.similarity)||r.similarity<minSimilarity||r.similarity>1)))continue;seen.add(r.id);rank++;const hit=byId.get(r.id)||{id:r.id,created_at:r.created_at,score:0,sources:[]};hit.score+=1/(60+rank);hit.sources.push(source);byId.set(r.id,hit)}};
  add(lexical,"lexical");add(semantic,"semantic");
  return [...byId.values()].sort((a,b)=>b.score-a.score||a.id.localeCompare(b.id)).slice(0,limit);
}
