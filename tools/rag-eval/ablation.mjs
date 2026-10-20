// Offline replay of captured candidates, not a new retrieval/model execution.
import {createHash} from 'node:crypto';
import {retrievalQuality} from './quality.mjs';
import {reviewProvenance} from './review-provenance.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
const identity = chunk => `${chunk.documentVersion}:${chunk.contentSha256}`;
const tieKey = evidence => `${evidence.source}\n${evidence.title}\n${evidence.excerpt}`;
const compareKey = (a,b) => a < b ? -1 : a > b ? 1 : 0;

function snapshot(report) {
  if (!Array.isArray(report?.corpus?.approved) || !report.corpus.approved.length
      || !Array.isArray(report.results) || !report.results.length
      || sha(JSON.stringify(report.corpus.approved)) !== report.corpus.sha256
      || report.summary?.corpusStable !== true) throw new Error('Complete stable frozen corpus required');
  const chunks = new Map();
  for (const document of report.corpus.approved) {
    if (!Array.isArray(document.chunks)) throw new Error('Complete corpus chunks required');
    for (const chunk of document.chunks) {
      if (typeof chunk.documentVersion !== 'string' || !chunk.documentVersion
          || typeof chunk.text !== 'string' || !chunk.text.trim() || sha(chunk.text) !== chunk.contentSha256)
        throw new Error('Invalid frozen chunk identity');
      const key = identity(chunk);
      if (chunks.has(key)) throw new Error('Ambiguous frozen chunk identity');
      chunks.set(key, {...chunk,title:document.title});
    }
  }
  const cases = new Map();
  for (const result of report.results) {
    if (typeof result.id !== 'string' || !result.id || cases.has(result.id)
        || typeof result.query !== 'string' || !result.query.trim()
        || !Array.isArray(result.candidates) || !Array.isArray(result.selectedEvidence))
      throw new Error('Invalid or duplicate report case');
    cases.set(result.id,result);
  }
  return {chunks,cases};
}

function checkedEvidence(evidence,chunks) {
  if (!Array.isArray(evidence.chunkReferences) || evidence.chunkReferences.length !== 1)
    throw new Error('Evidence identity missing or ambiguous');
  const chunk = evidence.chunkReferences[0], origin = chunks.get(identity(chunk));
  if (!origin || typeof evidence.excerpt !== 'string' || sha(evidence.excerpt) !== chunk.contentSha256
      || origin.text !== evidence.excerpt || origin.title !== evidence.title || origin.source !== evidence.source)
    throw new Error('Evidence does not match frozen corpus');
  return {...evidence,chunk};
}

function mean(values) {
  const known=values.filter(value=>Number.isFinite(value));
  return {value:known.length ? known.reduce((sum,value)=>sum+value,0)/known.length : null,evaluated:known.length};
}

export function compareRetrievalRuns(reports,labels=null,{k=3,rrfKs=[10,30,60,100]}={}) {
  if (!Array.isArray(reports) || !reports.length || !Number.isInteger(k) || k<1 || k>100
      || !Array.isArray(rrfKs) || !rrfKs.length || new Set(rrfKs).size!==rrfKs.length
      || rrfKs.some(value=>!Number.isInteger(value)||value<1||value>10000)) throw new Error('Invalid ablation settings');
  const snapshots=reports.map(snapshot), reference=reports[0], referenceCases=snapshots[0].cases;
  for (let i=1;i<reports.length;i++) {
    if (reports[i].corpus.sha256!==reference.corpus.sha256 || snapshots[i].cases.size!==referenceCases.size
        || [...referenceCases].some(([id,item])=>snapshots[i].cases.get(id)?.query!==item.query))
      throw new Error('Runs must use identical frozen corpus and case queries');
  }
  const reviewed=new Map();
  if (labels!==null) {
    if (labels.schemaVersion!==1 || labels.corpusSha256!==reference.corpus.sha256 || !Array.isArray(labels.cases))
      throw new Error('Labels must match the frozen corpus');
    for (const label of labels.cases) {
      const item=referenceCases.get(label.id);
      if (!item || reviewed.has(label.id) || label.exhaustive!==true || !Array.isArray(label.relevant)
          || !(label.querySha256===sha(item.query) || label.query===item.query)
          || label.querySha256!==undefined && label.querySha256!==sha(item.query)
          || label.query!==undefined && label.query!==item.query
          || label.relevant.some(chunk=>!snapshots[0].chunks.has(identity(chunk))))
        throw new Error('Exhaustive labels must bind exact case query and corpus identities');
      reviewed.set(label.id,label);
    }
  }
  const runs=reports.map((report,index)=>{
    const {chunks}=snapshots[index];
    const cases=report.results.map(item=>{
      const candidates=item.candidates.map(candidate=>checkedEvidence(candidate,chunks));
      const ids=new Set();
      for (const candidate of candidates) {
        if (ids.has(identity(candidate.chunk))) throw new Error('Duplicate candidate identity');
        ids.add(identity(candidate.chunk));
      }
      const route=field=>{
        const ranks=new Set();
        for (const candidate of candidates) {
          if (!Number.isInteger(candidate[field]) || candidate[field]<0) throw new Error('Invalid route rank');
          if (candidate[field]>0 && ranks.has(candidate[field])) throw new Error('Duplicate route rank');
          if (candidate[field]>0) ranks.add(candidate[field]);
        }
        return candidates.filter(candidate=>candidate[field]>0).sort((a,b)=>a[field]-b[field]);
      };
      const lexical=route('lexicalRank'), dense=route('semanticRank');
      const rankings={bm25:lexical,qdrant:dense};
      for (const constant of rrfKs) rankings[`rrf_${constant}_pre_gates`]=candidates
        .filter(candidate=>candidate.lexicalRank>0||candidate.semanticRank>0)
        .map(candidate=>({...candidate,replayedScore:(candidate.lexicalRank ? 1/(constant+candidate.lexicalRank) : 0)
          +(candidate.semanticRank ? 1/(constant+candidate.semanticRank) : 0)}))
        .sort((a,b)=>b.replayedScore-a.replayedScore||compareKey(tieKey(a),tieKey(b)));
      rankings.captured_final=item.selectedEvidence.map(evidence=>checkedEvidence(evidence,chunks));
      if (rankings.captured_final.some(evidence=>!ids.has(identity(evidence.chunk))))
        throw new Error('Final evidence absent from captured candidate union');
      const label=reviewed.get(item.id);
      return {id:item.id,querySha256:sha(item.query),
        dependencies:{lexical:item.lexicalStatus??'UNKNOWN',semantic:item.semanticStatus??'UNKNOWN',rerank:item.rerankStatus??'UNKNOWN'},
        rankings:Object.fromEntries(Object.entries(rankings).map(([name,ranking])=>[name,{
          identities:[...new Set(ranking.map(candidate=>identity(candidate.chunk)))].slice(0,k),
          quality:label ? retrievalQuality(ranking.map(candidate=>candidate.chunk),label,k) : null,
        }]))};
    });
    const strategies=Object.keys(cases[0]?.rankings??{});
    return {reportSha256:sha(JSON.stringify(report)),runtimeSha256:sha(JSON.stringify(report.runtime??null)),
      labelledCases:reviewed.size,unlabelledCases:cases.length-reviewed.size,
      strategies:Object.fromEntries(strategies.map(name=>{
        const quality=cases.map(item=>item.rankings[name].quality).filter(Boolean);
        return [name,{recallAtK:mean(quality.map(item=>item.recallAtK)),
          precisionAtK:mean(quality.map(item=>item.precisionAtK)),mrr:mean(quality.map(item=>item.reciprocalRank)),
          noCoverageFalseSelections:quality.filter(item=>item.noCoverageFalseSelection).length}];
      })),cases};
  });
  const differences=runs.slice(1).map((run,index)=>({baseline:0,comparison:index+1,
    finalTopKChanged:run.cases.filter(item=>JSON.stringify(item.rankings.captured_final.identities)!==
      JSON.stringify(runs[0].cases.find(other=>other.id===item.id).rankings.captured_final.identities)).length,
    cases:run.cases.length}));
  return {schemaVersion:1,corpusSha256:reference.corpus.sha256,k,rrfKs,
    review:{status:labels?.reviewStatus??'UNLABELLED',reviewer:labels?.reviewer??null,split:labels?.split??'UNSPECIFIED',
      ...reviewProvenance(labels)},
    interpretation:'Offline replay of captured top-depth candidates, not a fresh retrieval or latency benchmark. RRF variants are before safety/negation gates, semantic reserve and rerank; captured_final is the actual saved result. Scores require exhaustive query-bound labels; no answer faithfulness or clinical accuracy is inferred.',
    runs,differences};
}
