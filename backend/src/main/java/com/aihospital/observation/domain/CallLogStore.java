package com.aihospital.observation.domain;

import com.aihospital.shared.model.Models.CallLog;
import java.util.List;

public interface CallLogStore {
    List<CallLog> calls();
    default List<CallLog> calls(String traceId) { return calls().stream().filter(c -> traceId.equals(c.traceId())).toList(); }
    void record(CallLog call);
}
