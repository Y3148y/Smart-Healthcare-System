import {dirname,resolve,relative,sep} from 'node:path';
import {realpathSync} from 'node:fs';

/** Resolve a new report destination, refusing paths outside the ignored local report directory. */
export function reviewOutputPath(projectRoot,requestedPath) {
  if(typeof requestedPath!=='string'||!requestedPath.trim()) throw new Error('Output path is required');
  const root=realpathSync(projectRoot);
  const reportDirectory=realpathSync(resolve(root,'reports','rag'));
  const reportRelative=relative(root,reportDirectory);
  if(reportRelative==='..'||reportRelative.startsWith(`..${sep}`)||resolve(root,reportRelative)!==reportDirectory)
    throw new Error('Local report directory must remain inside the project');
  const output=resolve(root,requestedPath);
  if(dirname(output)!==reportDirectory) throw new Error('Review output must be written directly under reports/rag');
  if(realpathSync(dirname(output))!==reportDirectory) throw new Error('Review output directory changed unexpectedly');
  return output;
}
