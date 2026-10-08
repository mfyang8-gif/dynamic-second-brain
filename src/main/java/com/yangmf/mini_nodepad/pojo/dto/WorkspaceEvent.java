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
}