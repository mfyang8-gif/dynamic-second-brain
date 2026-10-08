// service/FileUploadService.java
package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.enums.UploadModule;
import com.yangmf.mini_nodepad.pojo.vo.UploadResultVO;
import org.springframework.web.multipart.MultipartFile;

public interface FileUploadService {

    UploadResultVO upload(MultipartFile file, UploadModule module, Long userId);
}