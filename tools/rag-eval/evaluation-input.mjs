import {readFileSync,realpathSync,statSync} from 'node:fs';
import {dirname,extname,isAbsolute,relative,resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {validateCases} from './lib.mjs';

function within(root,file) {
  const path=relative(root,file);
  return path!=='' && path!=='..' && !path.startsWith('..'+(process.platform==='win32'?'\\':'/')) && !isAbsolute(path);
}

/** Built-in filename or explicit local report dataset; never read arbitrary paths. */
export function loadEvaluationInput(args,{directory,reportsDirectory}) {
  let file,root;
  if(args.length===0 || (args.length===1 && !args[0].startsWith('--'))) {
    file=resolve(directory,args[0]??'cases.json');root=realpathSync(directory);
    if(dirname(file)!==resolve(directory)) throw new Error('BUILTIN_DATASET_PATH_REQUIRED');
  } else if(args.length===2 && args[0]==='--dataset') {
    file=resolve(args[1]);root=realpathSync(reportsDirectory);
  } else throw new Error('USAGE: node cli.mjs [builtin.json | --dataset reports/rag/local.json]');
  const actual=realpathSync(file);
  if(!within(root,actual) || extname(actual).toLowerCase()!=='.json') throw new Error('LOCAL_DATASET_PATH_REQUIRED');
  const info=statSync(actual);
  if(!info.isFile() || info.size>1024*1024) throw new Error('INVALID_DATASET_SIZE');
  const bytes=readFileSync(actual);
  const dataset=validateCases(JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes)));
  return {dataset,datasetSha256:createHash('sha256').update(bytes).digest('hex')};
}

// Runtime mode/status are last-query observations, so they are deliberately
// excluded. This detects changes only in fields exposed by the current API.
export function runtimeIdentity(runtime) {
  const keys=['embeddingConfigured','embeddingModel','rerankEnabled','rerankConfigured','rerankModel',
    'requireSemantic','requireRerank','unrerankedDocumentDiversity'];
  return Object.fromEntries(keys.map(key=>[key,runtime?.[key]??null]));
}

export function runIntegrity({before,after,runtimeBefore,runtimeAfter,publicationBefore,publicationAfter}) {
  for(const snapshot of [before,after]) {
    if(!Number.isSafeInteger(snapshot?.sourceRevision) || snapshot.sourceRevision<0
        || !Number.isSafeInteger(snapshot.publicationVersion) || snapshot.publicationVersion<0)
      throw new Error('INVALID_CORPUS_OBSERVATION');
  }
  for(const state of [publicationBefore,publicationAfter]) {
    if(!state || !Number.isSafeInteger(state.pointerVersion) || state.pointerVersion<0
        || !['VERSIONED','LEGACY_UNPUBLISHED'].includes(state.mode)
        || (state.mode==='VERSIONED' ? typeof state.activeReleaseId!=='string' || !state.activeReleaseId
          : state.activeReleaseId!==null)) throw new Error('INVALID_PUBLICATION_OBSERVATION');
  }
  for(const runtime of [runtimeBefore,runtimeAfter]) {
    if(typeof runtime?.embeddingModel!=='string' || !['true','false'].includes(runtime.rerankEnabled))
      throw new Error('INVALID_RUNTIME_OBSERVATION');
  }
  return {
    corpusStable:before.sha256===after.sha256 && before.sourceRevision===after.sourceRevision,
    observedRuntimeStable:JSON.stringify(runtimeIdentity(runtimeBefore))===JSON.stringify(runtimeIdentity(runtimeAfter)),
    publicationStable:publicationBefore.pointerVersion===publicationAfter.pointerVersion
      && publicationBefore.activeReleaseId===publicationAfter.activeReleaseId
      && publicationBefore.mode===publicationAfter.mode
      && before.publicationVersion===publicationBefore.pointerVersion
      && after.publicationVersion===publicationAfter.pointerVersion,
  };
}
