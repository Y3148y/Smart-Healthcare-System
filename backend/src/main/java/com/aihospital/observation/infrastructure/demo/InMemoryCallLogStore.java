package com.aihospital.observation.infrastructure.demo;

import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.CallLog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class InMemoryCallLogStore implements CallLogStore {
    private final List<CallLog> calls = Collections.synchronizedList(new ArrayList<>());
    @Override public List<CallLog> calls() { synchronized (calls) { return List.copyOf(calls); } }
    @Override public void record(CallLog call) { calls.add(0, call); }
}
