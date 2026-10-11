import com.aihospital.knowledge.domain.MarkdownChunker;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

// Runs the application chunker against the public drafts; checks mechanics, not clinical accuracy.
class ChunkInspection {
  private static final Pattern FRONT_MATTER = Pattern.compile("(?s)^---\\n.*?\\n---\\n");
  private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.+");

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Usage: ChunkInspection <corpus-directory>");
    var chunker=new MarkdownChunker();
    int documents=0,total=0,shortChunks=0,longest=0,overlappingWindows=0;
    try(var paths=Files.walk(Path.of(args[0]))) {
      for(var path:paths.filter(p->p.getFileName().toString().equals("document.zh-CN.md")).sorted().toList()) {
        String text=Files.readString(path,StandardCharsets.UTF_8).replace("\r\n","\n");
        var frontMatter=FRONT_MATTER.matcher(text);
        if(!frontMatter.find())throw new IllegalStateException(path+": missing front matter");
        String body=text.substring(frontMatter.end());
        var chunks=chunker.split(path.getParent().toString(),body.lines().findFirst().orElseThrow(),"local-source",body);
        if(chunks.isEmpty())throw new IllegalStateException(path+": no chunks");
        var ids=new HashSet<String>();
        int previousStart=-1;
        int previousEnd=-1;
        var covered=new BitSet(body.codePointCount(0,body.length()));
        for(var chunk:chunks) {
          int length=chunk.text().codePointCount(0,chunk.text().length());
          int bodyLength=body.codePointCount(0,body.length());
          if(chunk.sectionPath().isEmpty() || length>MarkdownChunker.MAX_LENGTH || length==0
              || chunk.start()<0 || chunk.end()>bodyLength || chunk.start()>=chunk.end()
              || chunk.start()<=previousStart || !ids.add(chunk.chunkId()))
            throw new IllegalStateException(path+": invalid chunk position or identity");
          int from=body.offsetByCodePoints(0,chunk.start());
          int to=body.offsetByCodePoints(0,chunk.end());
          if(!body.substring(from,to).equals(chunk.text())
              || !MarkdownChunker.sha256(chunk.text()).equals(chunk.contentSha256())
              || !MarkdownChunker.VERSION.equals(chunk.chunkingVersion()))
            throw new IllegalStateException(path+": chunk differs from source body");
          if(chunk.text().contains("provenanceSha256") || chunk.text().contains("PENDING_REVIEW"))
            throw new IllegalStateException(path+": audit metadata leaked into body");
          covered.set(chunk.start(),chunk.end());
          if(previousEnd>chunk.start())overlappingWindows++;
          previousStart=chunk.start();
          previousEnd=chunk.end();
          if(length<80)shortChunks++;
          longest=Math.max(longest,length);
        }
        boolean fenced=false;
        int offset=0;
        for(String line:body.split("\n",-1)) {
          String stripped=line.strip();
          boolean heading=!fenced && HEADING.matcher(stripped).matches();
          if(!heading) {
            int point=body.codePointCount(0,offset);
            for(int i=0;i<line.length();) {
              int character=line.codePointAt(i);
              if(!Character.isWhitespace(character) && !covered.get(point))
                throw new IllegalStateException(path+": source content omitted near code point "+point);
              point++;
              i+=Character.charCount(character);
            }
          }
          if(stripped.startsWith("```") || stripped.startsWith("~~~"))fenced=!fenced;
          offset+=line.length()+1;
        }
        if(!chunks.equals(chunker.split(path.getParent().toString(),body.lines().findFirst().orElseThrow(),"local-source",body)))
          throw new IllegalStateException(path+": nondeterministic chunks");
        System.out.printf("%s bodyCodePoints=%d chunks=%d shortUnder80=%d maxChunk=%d%n",
          path.getParent().getFileName(),body.codePointCount(0,body.length()),chunks.size(),
          chunks.stream().filter(c->c.text().codePointCount(0,c.text().length())<80).count(),
          chunks.stream().mapToInt(c->c.text().codePointCount(0,c.text().length())).max().orElse(0));
        documents++;total+=chunks.size();
      }
    }
    if(documents==0)throw new IllegalStateException("No corpus documents found");
    System.out.printf("documents=%d totalChunks=%d shortUnder80=%d longest=%d overlappingWindows=%d maxLength=%d overlapSetting=%d chunker=%s%n",
      documents,total,shortChunks,longest,overlappingWindows,MarkdownChunker.MAX_LENGTH,MarkdownChunker.OVERLAP,MarkdownChunker.VERSION);
  }
}
