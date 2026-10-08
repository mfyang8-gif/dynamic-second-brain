// ai/component/studio/AbstractStudioGenerator.java
package com.yangmf.mini_nodepad.ai.component.studio;

import com.yangmf.mini_nodepad.ai.component.StudioGenerator;

import com.yangmf.mini_nodepad.pojo.dto.studio.payload.ArtifactPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.BaseStudioRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

@Slf4j
public abstract class AbstractStudioGenerator<REQ extends BaseStudioRequest, RES extends ArtifactPayload>
        implements StudioGenerator<REQ, RES> {

    @Override
    @SuppressWarnings("unchecked")
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 1.5, random = true)
    )

    public RES generate(BaseStudioRequest request, String context) {
        long start = System.currentTimeMillis();
        REQ typedRequest = (REQ) request;
        log.info("Studio 开始生成 | type={}, topic={}", getType(), request.getTopic());

        RES result = doGenerate(typedRequest, context);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Studio 生成完成 | type={}, elapsed={}ms", getType(), elapsed);
        return result;
    }

    protected abstract RES doGenerate(REQ request, String context);
}