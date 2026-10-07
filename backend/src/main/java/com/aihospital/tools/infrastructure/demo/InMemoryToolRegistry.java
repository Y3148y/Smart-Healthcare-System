package com.aihospital.tools.infrastructure.demo;

import com.aihospital.shared.model.Models.Tool;
import com.aihospital.tools.domain.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class InMemoryToolRegistry implements ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    public InMemoryToolRegistry() {
        add("symptom_tag_search","症状标签检索","按症状关键词匹配科室、权重和危险信号",1);
        add("medical_knowledge_retrieve","医学知识库检索","从 RAG 知识片段返回可引用的医疗资料",2);
        add("department_search","科室查询","查询目录是否配置或启用科室，并区分医生、排班及模拟号源状态；不判断医学适用性",3);
        add("doctor_schedule_search","医生出诊排班查询","返回推荐科室可预约的医生与号源",4);
    }
    private void add(String code, String name, String description, int order) {
        tools.put(code, new Tool(code, name, description, true, order));
    }
    @Override public List<Tool> tools() { return List.copyOf(tools.values()); }
    @Override public Tool toggle(String code) {
        Tool old = tools.get(code);
        if (old == null) throw new IllegalArgumentException("工具不存在");
        Tool next = new Tool(old.code(), old.name(), old.description(), !old.enabled(), old.order());
        tools.put(code, next);
        return next;
    }
}
