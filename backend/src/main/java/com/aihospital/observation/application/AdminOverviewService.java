package com.aihospital.observation.application;

import com.aihospital.shared.model.Models.Dashboard;
import com.aihospital.observation.domain.OverviewStore;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.observation.domain.CallLogStore;
import org.springframework.stereotype.Service;

@Service
public class AdminOverviewService {
    private final OverviewStore store;
    private final KnowledgeCatalog knowledge;
    private final CallLogStore calls;

    public AdminOverviewService(OverviewStore store, KnowledgeCatalog knowledge, CallLogStore calls) {
        this.store = store;
        this.knowledge = knowledge;
        this.calls = calls;
    }

    public Dashboard dashboard() {
        int sessions = store.sessions();
        int appointments = store.appointments();
        int completed = store.completedSessions();
        int fromTriage = store.triageAppointments();
        double acceptanceRate = sessions == 0 ? 0 : Math.round(fromTriage * 1000.0 / sessions) / 10.0;
        return new Dashboard(sessions, appointments, completed, acceptanceRate,
                knowledge.documents().size(), store.toolCalls());
    }
}
