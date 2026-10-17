// Exact source-anchored engineering labels, not medical inference or synonym matching.
const fail=()=>{throw new Error('Invalid source-bound event review');};
const ratio=(n,d)=>d?n/d:null;
const textFields=['symptom','bodySite','onset','duration','trigger','severity'];
const enums={subject:['SELF','OTHER','UNKNOWN'],temporality:['CURRENT','PAST','UNKNOWN'],assertion:['AFFIRMED','NEGATED','UNCERTAIN']};
function events(messages,items){
  if(!Array.isArray(items)||items.length>20)fail();
  const sources=new Map(messages.map(message=>[message.id,message]));
  const byAnchor=new Map();
  for(const event of items){
    if(!event||Object.keys(event).some(key=>![...textFields,...Object.keys(enums),'sourceRefs'].includes(key))
        ||!Array.isArray(event.sourceRefs)||event.sourceRefs.length<1||event.sourceRefs.length>5)fail();
    const refs=event.sourceRefs.map(ref=>{
      const source=sources.get(ref?.messageId);
      if(!source||source.role!=='USER'||typeof ref.quote!=='string'||!ref.quote.trim()
          ||!source.content.includes(ref.quote)||Object.keys(ref).some(key=>!['messageId','quote'].includes(key)))fail();
      return {messageId:ref.messageId,quote:ref.quote};
    }).sort((a,b)=>{const left=JSON.stringify(a),right=JSON.stringify(b);return left<right?-1:left>right?1:0;});
    if(new Set(refs.map(ref=>JSON.stringify(ref))).size!==refs.length)fail();
    const quotes=refs.map(ref=>ref.quote).join('\n');
    for(const key of textFields){
      if(event[key]!==null&&(typeof event[key]!=='string'||!event[key].trim()||!quotes.includes(event[key])))fail();
    }
    if(typeof event.symptom!=='string'||!event.symptom.trim())fail();
    for(const [key,values] of Object.entries(enums))if(!values.includes(event[key]))fail();
    const anchor=JSON.stringify({symptom:event.symptom,sourceRefs:refs});
    if(byAnchor.has(anchor))fail(); // Ambiguous duplicate anchors need reviewer resolution, not guessed pairing.
    byAnchor.set(anchor,JSON.stringify(Object.fromEntries([...textFields,...Object.keys(enums)].map(key=>[key,event[key]]))));
  }
  return byAnchor;
}
export function eventQuality(messages,actual,review){
  if(review?.exhaustive!==true)throw new Error('Exhaustive event labels required');
  const predicted=events(messages,actual),expected=events(messages,review.events);
  let hits=0,matched=0;const correct={subject:0,temporality:0,assertion:0};
  for(const [anchor,value] of predicted){
    const gold=expected.get(anchor);if(gold===undefined)continue;
    matched++;if(value===gold)hits++;
    const proposal=JSON.parse(value),label=JSON.parse(gold);
    for(const key of Object.keys(correct))if(proposal[key]===label[key])correct[key]++;
  }
  return {predicted:predicted.size,expected:expected.size,hits,matchedAnchors:matched,
    precision:ratio(hits,predicted.size),recall:ratio(hits,expected.size),
    f1:ratio(2*hits,predicted.size+expected.size),
    attribution:Object.fromEntries(Object.entries(correct).map(([key,count])=>[key,{correct:count,evaluated:matched,value:ratio(count,matched)}]))};
}
export function summarizeEvents(rows){
  const total=key=>rows.reduce((sum,row)=>sum+row[key],0);
  const hits=total('hits'),predicted=total('predicted'),expected=total('expected');
  return {evaluatedCases:rows.length,exactEventPrecision:ratio(hits,predicted),exactEventRecall:ratio(hits,expected),
    exactEventF1:ratio(2*hits,predicted+expected),
    attribution:Object.fromEntries(Object.keys(enums).map(key=>{
      const correct=rows.reduce((n,row)=>n+row.attribution[key].correct,0),evaluated=rows.reduce((n,row)=>n+row.attribution[key].evaluated,0);
      return [key,{correct,evaluated,value:ratio(correct,evaluated)}];
    }))};
}
