package com.aihospital.observation.domain;

import com.aihospital.shared.model.Models.CallLog;
import java.util.List;

public interface CallLogStore {
    List<CallLog> calls();
    void record(CallLog call);
}
