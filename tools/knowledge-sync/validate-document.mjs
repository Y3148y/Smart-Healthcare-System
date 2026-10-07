import {readMarkdownFile} from './lib/markdown-file.mjs';

// Offline inspection only: no login, network, database writes or approval.
try {
  const paths=process.argv.slice(2);
  if(!paths.length) throw new Error('DOCUMENT_PATH_REQUIRED');
  for(const path of paths) {
    console.log(JSON.stringify(readMarkdownFile(path).inspection));
  }
} catch(error) {
  console.error(/^[A-Z0-9_]+$/.test(error.message)?error.message:'DOCUMENT_VALIDATION_FAILED');
  process.exitCode=1;
}
