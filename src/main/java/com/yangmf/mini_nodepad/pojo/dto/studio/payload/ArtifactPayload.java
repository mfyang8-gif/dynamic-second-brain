package com.yangmf.mini_nodepad.pojo.dto.studio.payload;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "artifactType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = QuizPayload.class, name = "QUIZ"),
        @JsonSubTypes.Type(value = FlashcardPayload.class, name = "FLASHCARD"),
        @JsonSubTypes.Type(value = MindmapPayload.class, name = "MINDMAP"),
        @JsonSubTypes.Type(value = ReportPayload.class, name = "REPORT"),
        @JsonSubTypes.Type(value = PresentationPayload.class, name = "PRESENTATION")
})
public abstract class ArtifactPayload {
    private String artifactType;

    protected ArtifactPayload(ArtifactType type) {
        this.artifactType = type.getCode();
    }

    protected ArtifactPayload() {
    }
}