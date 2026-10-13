// pojo/dto/WorkspaceEvent.java
package com.yangmf.mini_nodepad.pojo.dto;

public record WorkspaceEvent(
        String type,
        String artifactId,
        String data
) {
    public static WorkspaceEvent artifactCompleted(String artifactId, String data) {
        return new WorkspaceEvent("ARTIFACT_COMPLETED", artifactId, data);
    }

    public static WorkspaceEvent artifactFailed(String artifactId, String errorMsg) {
        return new WorkspaceEvent("ARTIFACT_FAILED", artifactId, errorMsg);
    }

    public static WorkspaceEvent artifactProgress(String artifactId, String progressJson) {
        return new WorkspaceEvent("ARTIFACT_PROGRESS", artifactId, progressJson);
    }

    /**
     * 命名事件：data 就是标题字符串本身。
     * 生成中推"暂定名"，生成完成前再推一次"精修名"，前端原地替换加载态文案。
     */
    public static WorkspaceEvent artifactTitled(String artifactId, String dynamicTitle) {
        return new WorkspaceEvent("ARTIFACT_TITLED", artifactId, dynamicTitle);
    }
}