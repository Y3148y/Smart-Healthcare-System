package com.aihospital.knowledge.domain;

public final class KnowledgeImportConflictException extends RuntimeException {
    public KnowledgeImportConflictException() {
        super("该导入请求键已用于不同的资料内容或来源声明，请刷新后核对已有资料");
    }
}
