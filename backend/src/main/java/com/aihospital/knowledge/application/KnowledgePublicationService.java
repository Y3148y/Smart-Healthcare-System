package com.aihospital.knowledge.application;

import com.aihospital.knowledge.domain.*;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.mybatis.KnowledgeDocumentMapper;
import com.aihospital.knowledge.infrastructure.mybatis.KnowledgeReleaseMapper;
import com.aihospital.knowledge.infrastructure.qdrant.QdrantSemanticIndex;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Immutable inputs and CAS publication. Network index builds run outside database transactions. */
@Service
public class KnowledgePublicationService {
    public record Payload(List<StoredKnowledgeDocument> documents,Map<String,String> approvalIds) {
        public Payload { documents=List.copyOf(documents);approvalIds=Map.copyOf(approvalIds); }
    }
    public record Active(long pointerVersion,String releaseId,List<StoredKnowledgeDocument> documents) {}
    private final KnowledgeDocumentStore documents;
    private final KnowledgeDocumentMapper history;
    private final KnowledgeReleaseMapper releases;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final LexicalSearch lexical;
    private final QdrantSemanticIndex semantic;
    @Value("${ai.retrieval.require-semantic:false}") private boolean requireSemantic;
    public KnowledgePublicationService(KnowledgeDocumentStore documents,KnowledgeDocumentMapper history,
            KnowledgeReleaseMapper releases,ObjectMapper json,PlatformTransactionManager manager,LexicalSearch lexical,QdrantSemanticIndex semantic) {
        this.documents=documents;this.history=history;this.releases=releases;this.json=json;
        tx=new TransactionTemplate(manager);this.lexical=lexical;this.semantic=semantic;
    }
    public List<Map<String,Object>> list() { return releases.list(); }
    public List<Map<String,Object>> history(String id) {
        if(documents.find(id)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"知识资料不存在");
        return history.approvalHistory(id);
    }
    public long pointerVersion() { return number(releases.pointer(),"pointer_version"); }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Knowledge publication serialization failed");} }
    private String hash(String value) { try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Knowledge hash unavailable");} }
    private long number(Map<String,Object> row,String key) {
        if(row==null||!(row.get(key) instanceof Number value))throw new IllegalStateException("Publication state is unavailable");
        return value.longValue();
    }
    private String approvalId(StoredKnowledgeDocument document) {
        var events=history.approvalHistory(document.id());
        if(events.isEmpty())return "LEGACY:"+hash(encode(document));
        var event=events.get(0);
        if("WITHDRAW".equals(event.get("event_type")))throw new IllegalStateException("Approval history contradicts current state");
        return String.valueOf(event.get("id"));
    }
    private Payload payload(Map<String,Object> row) {
        if(row==null||!hash(String.valueOf(row.get("snapshot_json"))).equals(row.get("snapshot_sha256")))throw new IllegalStateException("Publication snapshot integrity failed");
        try{return json.readValue(String.valueOf(row.get("snapshot_json")),Payload.class);}catch(Exception e){throw new IllegalStateException("Publication snapshot is invalid");}
    }
    private List<StoredKnowledgeDocument> authorized(Payload payload,List<StoredKnowledgeDocument> current,boolean strict) {
        var byId=new HashMap<String,StoredKnowledgeDocument>();current.forEach(d->byId.put(d.id(),d));
        var accepted=new ArrayList<StoredKnowledgeDocument>();
        for(var old:payload.documents()) {
            var now=byId.get(old.id());
            boolean valid=now!=null&&"READY".equals(now.status())&&encode(old).equals(encode(now))
                    &&Objects.equals(payload.approvalIds().get(old.id()),approvalId(now));
            if(valid)accepted.add(old);
            else if(strict)throw new ResponseStatusException(HttpStatus.CONFLICT,"发布所需资料已变化或撤回，请重新构建");
        }
        return List.copyOf(accepted);
    }
    /** Null explicitly identifies a legacy database not yet switched to publication-managed reads. */
    public Active active() {
        return tx.execute(status->{
            var current=documents.snapshot();var pointer=releases.pointer();Object id=pointer.get("active_release_id");
            if(id==null)return null;
            var release=releases.find(String.valueOf(id));
            if(release==null||!"READY".equals(release.get("build_status")))throw new IllegalStateException("Active publication is invalid");
            return new Active(number(pointer,"pointer_version"),String.valueOf(id),authorized(payload(release),current.documents(),false));
        });
    }
    public Map<String,Object> buildAndPublish(long expectedVersion) {
        String id=tx.execute(status->{
            var snapshot=documents.snapshot();var pointer=releases.lockPointer();
            if(number(pointer,"pointer_version")!=expectedVersion)throw new ResponseStatusException(HttpStatus.CONFLICT,"发布版本已变化");
            var ready=snapshot.documents().stream().filter(d->"READY".equals(d.status())).sorted(Comparator.comparing(StoredKnowledgeDocument::id)).toList();
            var approvals=new TreeMap<String,String>();ready.forEach(d->approvals.put(d.id(),approvalId(d)));
            String encoded=encode(new Payload(ready,approvals)),key=UUID.randomUUID().toString();
            if(releases.insert(key,snapshot.publishedRevision(),expectedVersion,encoded,hash(encoded))!=1)throw new IllegalStateException("Publication preparation failed");
            return key;
        });
        return buildAndActivate(id,expectedVersion,false);
    }
    public Map<String,Object> rollback(String id,long expectedVersion) { return buildAndActivate(id,expectedVersion,true); }
    private Map<String,Object> buildAndActivate(String id,long expectedVersion,boolean rollback) {
        var row=releases.find(id);
        if(row==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"发布版本不存在");
        if(rollback&&!"READY".equals(row.get("build_status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"只能回滚到完整构建版本");
        var fixed=payload(row);Map<String,Object> receipt;
        if(rollback)tx.executeWithoutResult(status->{
            var current=documents.snapshot();var pointer=releases.lockPointer();
            if(number(pointer,"pointer_version")!=expectedVersion)throw new ResponseStatusException(HttpStatus.CONFLICT,"发布版本已变化");
            authorized(fixed,current.documents(),true);
        });
        try {receipt=buildIndexes(fixed);}
        catch(RuntimeException unavailable){
            if(!rollback)tx.executeWithoutResult(s->releases.finish(id,"FAILED",encode(Map.of("status","INDEX_BUILD_FAILED"))));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"索引构建未完成，活动版本未切换");
        }
        if(!rollback)tx.executeWithoutResult(s->{if(releases.finish(id,"READY",encode(receipt))!=1)throw new IllegalStateException("Publication completion state changed");});
        return tx.execute(status->{
            var snapshot=documents.snapshot();var pointer=releases.lockPointer();
            if(number(pointer,"pointer_version")!=expectedVersion)throw new ResponseStatusException(HttpStatus.CONFLICT,"发布版本已变化，未切换活动指针");
            var target=releases.find(id);
            if(target==null||!"READY".equals(target.get("build_status"))||!Objects.equals(target.get("snapshot_sha256"),row.get("snapshot_sha256")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"目标发布版本已不可用");
            if(!rollback&&snapshot.publishedRevision()!=number(row,"source_revision"))throw new ResponseStatusException(HttpStatus.CONFLICT,"构建期间审批发生变化，请重新构建");
            authorized(fixed,snapshot.documents(),true);
            if(releases.activate(id,expectedVersion)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"发布指针并发冲突");
            return Map.of("releaseId",id,"pointerVersion",expectedVersion+1,"status","ACTIVE","receipt",receipt);
        });
    }
    private Map<String,Object> buildIndexes(Payload fixed) {
        var local=new InMemoryKnowledgeCatalog(false);local.restorePersistedDocuments(fixed.documents());var corpus=local.approvedCorpus();
        var result=lexical.search("publication verification",corpus,1,0);
        if((!result.status().endsWith("READY")&&!result.status().endsWith("EMPTY_CORPUS"))||!lexical.verify(corpus))throw new IllegalStateException("Lexical build verification failed");
        String vectors="NOT_CONFIGURED";
        if(semantic.configured()&&!corpus.isEmpty()) {
            var origins=new HashMap<String,List<KnowledgeChunk>>();
            for(var document:fixed.documents())for(var chunk:local.documentChunks(document.id())) {
                String key=Bm25Retriever.key(new Evidence(document.title(),chunk.source(),chunk.text(),1));
                origins.computeIfAbsent(key,ignored->new ArrayList<>()).add(chunk);
            }
            synchronized(semantic) {
                semantic.registerChunkProvenance(origins);
                if(!semantic.ensureIndexed(corpus)||!semantic.verifyRemote(corpus))throw new IllegalStateException("Vector build verification incomplete");
            }
            vectors="VERIFIED";
        } else if(requireSemantic&&!corpus.isEmpty())throw new IllegalStateException("Semantic index is required");
        return Map.of("chunks",corpus.size(),"lexical",result.status(),"vector",vectors,"embeddingModel",semantic.modelName());
    }
    public Map<String,Object> state() {
        var pointer=releases.pointer();var result=new LinkedHashMap<String,Object>();
        result.put("pointerVersion",number(pointer,"pointer_version"));result.put("activeReleaseId",pointer.get("active_release_id"));
        result.put("mode",pointer.get("active_release_id")==null?"LEGACY_UNPUBLISHED":"VERSIONED");return result;
    }
    public void deleteExpired(String id,long expectedVersion) {
        tx.executeWithoutResult(status->{
            documents.snapshot();var pointer=releases.lockPointer();
            if(number(pointer,"pointer_version")!=expectedVersion||Objects.equals(pointer.get("active_release_id"),id))throw new ResponseStatusException(HttpStatus.CONFLICT,"不能清理活动或已变化版本");
            var kept=releases.latestReady();
            if(kept.contains(id)||releases.deleteExpired(id)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"至少保留最近三个完整版本，其他版本须满七日才可清理");
        });
    }
}
