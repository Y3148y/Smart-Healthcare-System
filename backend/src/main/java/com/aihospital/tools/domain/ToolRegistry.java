package com.aihospital.tools.domain;

import com.aihospital.shared.model.Models.Tool;
import java.util.List;

public interface ToolRegistry {
    List<Tool> tools();
    Tool toggle(String code);
}
