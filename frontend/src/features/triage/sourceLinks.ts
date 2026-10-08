/** Source metadata is untrusted; never expose embedded URL credentials. */
export function sourceLinks(source:string):{href:string,label:string}[]{
  const links=new Map<string,{href:string,label:string}>()
  for(const value of (source||'').split('|').map(item=>item.trim())){
    try{
      const url=new URL(value)
      if(['https:','http:'].includes(url.protocol)&&!url.username&&!url.password)
        links.set(url.href,{href:url.href,label:url.hostname})
    }catch{ /* Invalid metadata remains non-clickable. */ }
  }
  return [...links.values()]
}
