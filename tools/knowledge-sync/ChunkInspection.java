import com.aihospital.knowledge.domain.MarkdownChunker;
import com.aihospital.knowledge.domain.KnowledgeChunk;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

// Local inspection of actual drafts with the existing application chunker; no network or database.
class ChunkInspection {
  public static void main(String[] args) throws Exception {
    var chunker=new MarkdownChunker();
    int documents=0,total=0;
    try(var paths=Files.walk(Path.of(args[0]))) {
      for(var path:paths.filter(p->p.getFileName().toString().equals("document.zh-CN.md")).sorted().toList()) {
        String text=Files.readString(path,StandardCharsets.UTF_8).replace("\r\n","\n");
        String body=text.replaceFirst("(?s)^---\\n.*?\\n---\\n","");
        if(body.equals(text))throw new IllegalStateException("Missing front matter");
        var chunks=chunker.split(path.getParent().toString(),body.lines().findFirst().orElseThrow(),"local-source",body);
        if(chunks.isEmpty())throw new IllegalStateException("No chunks");
        for(var chunk:chunks) {
          if(chunk.sectionPath().isEmpty() || chunk.text().codePointCount(0,chunk.text().length())>MarkdownChunker.MAX_LENGTH)
            throw new IllegalStateException("Invalid chunk");
          if(chunk.text().contains("provenanceSha256") || chunk.text().contains("PENDING_REVIEW"))
            throw new IllegalStateException("Audit metadata leaked into body");
        }
        System.out.printf("%s body=%d chunks=%d maxChunk=%d%n",path.getParent().getFileName(),body.length(),chunks.size(),
          chunks.stream().mapToInt(c->c.text().codePointCount(0,c.text().length())).max().orElse(0));
        documents++;total+=chunks.size();
      }
    }
    System.out.printf("documents=%d totalChunks=%d maxLength=%d overlap=%d%n",documents,total,MarkdownChunker.MAX_LENGTH,MarkdownChunker.OVERLAP);
  }
}
