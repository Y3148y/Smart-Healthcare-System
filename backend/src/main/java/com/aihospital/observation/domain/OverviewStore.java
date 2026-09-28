package com.aihospital.observation.domain;

public interface OverviewStore {
    int sessions();
    int appointments();
    int completedSessions();
    int triageAppointments();
}
