// A declared label source is not authenticated reviewer identity or clinical approval.
export function reviewProvenance(packet) {
  const reviewerType=packet?.reviewerType??'UNSPECIFIED';
  if(!['AI_ASSISTED','HUMAN','UNSPECIFIED'].includes(reviewerType))
    throw new Error('Invalid reviewer type');
  if(packet?.reviewStatus==='AI_ASSISTED_REVIEWED' && reviewerType!=='AI_ASSISTED')
    throw new Error('AI-assisted review status requires an explicit AI-assisted reviewer type');
  return {reviewerType,reviewNotice:reviewerType==='AI_ASSISTED'
    ?'AI-assisted engineering labels; not human gold labels, clinical approval or validated population accuracy.'
    :'Reviewer type is a declaration, not authenticated identity or clinical competence; missing provenance is not assumed human.'};
}
