// pojo/dto/studio/payload/FlashcardPayload.java
package com.yangmf.mini_nodepad.pojo.dto.studio.payload;


import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.llm.FlashcardLlmResponse;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 抽认卡工件持久化负载。
 * 复用 FlashcardLlmResponse.Card 作为卡片结构，自身仅负责 artifactType 标识与 DB 存储。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FlashcardPayload extends ArtifactPayload {

    private List<FlashcardLlmResponse.Card> cards;

    public FlashcardPayload() {
        super(ArtifactType.FLASHCARD);
    }
}