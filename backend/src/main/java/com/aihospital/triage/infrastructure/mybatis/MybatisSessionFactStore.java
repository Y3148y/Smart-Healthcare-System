package com.aihospital.triage.infrastructure.mybatis;

import com.aihospital.triage.application.IntentInput;
import com.aihospital.triage.application.IntentValidator;
import com.aihospital.triage.application.SessionFactReducer;
import com.aihospital.triage.application.SessionFactStore;
import com.aihospital.triage.domain.*;
import com.aihospital.triage.domain.TriageRecords.Conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Staged observations become readable only with the turn's atomic COMPLETED transition. */
@Repository
@Transactional(isolation=Isolation.READ_COMMITTED)
public class MybatisSessionFactStore implements SessionFactStore {
    private final SessionFactMapper mapper;
    private final TriageStore conversations;
    private final IntentValidator validator;
    private final ObjectMapper json;
    private final SessionFactReducer reducer=new SessionFactReducer();
    public MybatisSessionFactStore(SessionFactMapper mapper,TriageStore conversations,IntentValidator validator,ObjectMapper json){
        this.mapper=mapper;this.conversations=conversations;this.validator=validator;this.json=json;
    }
    @Override public SessionFacts completed(String patient,String sessionId){
        owner(patient,sessionId);return read(mapper.completed(sessionId));
    }
    @Override public SessionFacts stage(String patient,Conversation conversation,IntentInput input,IntentExtraction proposal){
        String session=conversation.session().id();owner(patient,session);
        var current=conversations.latestTurn(session);
        if(current==null||conversation.latestTurn()==null||!current.id().equals(conversation.latestTurn().id())
                ||!"RUNNING".equals(current.status())||!current.expiresAt().isAfter(mapper.now()))
            throw new IllegalStateException("Fact observation no longer belongs to an active turn");
        var messages=conversations.messages(session);
        if(messages.size()!=conversation.messages().size()||!IntentInput.recent(messages).equals(input)
                ||!input.messages().get(input.messages().size()-1).messageId().equals(current.userMessageId()))
            throw new IllegalStateException("Fact source window has changed");
        try{
            // Repeat schema/source validation at the storage boundary; mock extractors cannot bypass it.
            var checked=validator.validate(json.writeValueAsString(proposal),input);
            var facts=reducer.merge(read(mapper.completed(session)),checked,input,messages.size());
            if(facts.events().size()>500||facts.conflicts().size()>100)
                throw new IllegalStateException("Fact history budget exceeded");
            String text=json.writeValueAsString(facts);
            if(text.length()>262144)throw new IllegalStateException("Fact snapshot budget exceeded");
            String hash=AnswerEvidence.hash(text);
            String sourceHash=AnswerEvidence.hash(json.writeValueAsString(input));
            var existing=mapper.forTurn(current.id());
            if(existing!=null){
                if(!hash.equals(existing.get("facts_hash"))||!sourceHash.equals(existing.get("source_window_hash")))
                    throw new IllegalStateException("Turn observation already staged differently");
                return read(existing);
            }
            mapper.insert(current.id(),session,facts.version(),sourceHash,hash,text);
            return facts;
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){
            throw new IllegalStateException("Cannot encode fact observation");
        }
    }
    private void owner(String patient,String session){
        if(mapper.lockSession(session,patient)==null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在");
    }
    private SessionFacts read(Map<String,Object> row){
        if(row==null)return SessionFacts.empty();
        Object value=row.get("facts_json");
        if(!(row.get("schema_version") instanceof Number schema)||schema.intValue()!=1
                ||!(value instanceof String text)||text.length()>262144||!AnswerEvidence.hash(text).equals(row.get("facts_hash")))
            throw new IllegalStateException("Invalid stored fact observation");
        try{
            var facts=json.readValue(text,SessionFacts.class);
            if(!(row.get("state_version") instanceof Number version)||version.longValue()!=facts.version())
                throw new IllegalStateException("Invalid stored fact version");
            if(facts.events().size()>500||facts.conflicts().size()>100)throw new IllegalStateException("Invalid stored fact budget");
            return facts;
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("Invalid stored fact schema");}
    }
}
