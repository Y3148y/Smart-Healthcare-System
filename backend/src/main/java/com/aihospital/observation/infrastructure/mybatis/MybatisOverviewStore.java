package com.aihospital.observation.infrastructure.mybatis;

import com.aihospital.observation.domain.OverviewStore;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisOverviewStore implements OverviewStore {
    private final OverviewMapper mapper;
    public MybatisOverviewStore(OverviewMapper mapper) { this.mapper = mapper; }
    @Override public int sessions() { return mapper.sessions(); }
    @Override public int appointments() { return mapper.appointments(); }
    @Override public int completedSessions() { return mapper.completedSessions(); }
    @Override public int triageAppointments() { return mapper.triageAppointments(); }
    @Override public int toolCalls() { return mapper.toolCalls(); }
}
