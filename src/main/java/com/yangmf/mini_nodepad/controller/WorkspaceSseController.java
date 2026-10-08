// controller/WorkspaceSseController.java
package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.context.BaseContext;

import com.yangmf.mini_nodepad.service.WorkspaceSseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/workspace")
@RequiredArgsConstructor
@Tag(name = "Workspace SSE", description = "全局 SSE 长连接通道")
public class WorkspaceSseController {

    private final WorkspaceSseService workspaceSseService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "建立全局 SSE 长连接")
    public SseEmitter stream() {
        Long userId = BaseContext.getCurrentId();
        return workspaceSseService.connect(userId);
    }
}