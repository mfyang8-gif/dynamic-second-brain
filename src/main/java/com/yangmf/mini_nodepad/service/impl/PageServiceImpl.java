package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.yangmf.mini_nodepad.aiservice.PageAssistant;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.enums.SourceTypeEnum;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.exception.ValidationException;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.dto.PageIngestDTO;
import com.yangmf.mini_nodepad.pojo.dto.PageQueryDTO;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.pojo.vo.PageVO;
import com.yangmf.mini_nodepad.result.PageResult;
import com.yangmf.mini_nodepad.service.PageAsyncService;
import com.yangmf.mini_nodepad.service.PageService;
import com.yangmf.mini_nodepad.utils.TextProcessManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PageServiceImpl extends ServiceImpl<PageMapper, Page> implements PageService {

    private final PageMapper pageMapper;

    private final PageAssistant pageAssistant;

    private final PageAsyncService pageAsyncService;

    private final TextProcessManager textCleaner;




    //TODO:智能插入，先判断是否已存在该内容，如果存在则合并+清洗，不存在则插入新

    //TODO：入库qdrant时候选择的结构以及字段

    /**
     *
     * TODO:. 权限与边界隔离（必须有，且必须建 Qdrant 索引）
     * userId (String)：绝对核心！向量库是全局共享的，搜索时必须强制带上 userId 过滤，否则用户 A 就能搜到用户 B 的私人笔记（哪怕你没加这层隔离，作为一个企业级项目也必须养成习惯）。
     *
     * bookId (String)：正如你所想，必加字段！用户经常会在“指定的一本书”或“指定的几个知识库”范围内进行搜索。
     *
     * 2. 时间排序与范围检索（必须转为时间戳存）
     * createdAt (Long / Integer)：存 Unix 时间戳（秒级）。
     *
     * 为什么需要？ 用户未来一定会搜“帮我总结一下最近一周关于Java的笔记”。大模型无法直接处理“最近一周”，必须靠 Qdrant 利用 createdAt >= timestamp 进行前置过滤！
     *
     * 3. 搜索结果瞬时展示（避免 MySQL 回表查询）
     * 当 Qdrant 返回相似度最高的 5 条结果时，前端需要立刻展示卡片，如果再去 MySQL 查一遍，延迟就太高了。
     *
     * title (String)：保存 AI 生成的或用户手写的标题。
     *
     * summary (String)：保存 AI 提取的百字摘要。
     *
     * sourceType (String)：(枚举的 name())，方便前端在搜索结果旁边展示不同的 Icon（比如网络摘录显示个地球，手动输入显示个键盘）。
     *
     * 🚫 千万别塞进 Qdrant 的“毒药”字段
     * 大正文 content (Text)：
     *
     * 绝对不要塞入 Payload！ Markdown 正文动辄几万字，放进 Payload 会瞬间撑爆 Qdrant 的内存，导致检索变慢。
     *
     * 正确做法：前端拿到 Qdrant 返回的 title 和 summary 列表后，用户点击某一条，前端再拿着 pointId（即 page.id）去请求 MySQL 拿全部的 content 详情。
     *
     * deleted 和 deletedAt (Integer/Date)：
     *
     * 不要放！ 向量库不应该搞“逻辑删除”。MySQL 里逻辑删除（deleted=1）的数据，在 Qdrant 里应该被直接物理删除（调用咱们写好的 qdrantTemplate.delete()）。别让垃圾数据浪费昂贵的向量算力。
     *
     * autoOptimize 和 allowAiModify：
     *
     * 这些纯粹是 Ingest 流水线和后续 Agent 任务调度的业务控制标记，跟“语义检索”毫无关系，放进去没有任何意义。
     * @param dto
     */
    //


    @Override
    public void ingestPage(PageIngestDTO dto) {
        String currentUserId = getCurrentUserId();

        // 1. 毫秒级的物理清洗兜底 (防止没开 AI 时存进去的全是脏字符)
        String fastCleanText = textCleaner.physicalClean(dto.getContent());

        Page page = new Page();
        BeanUtils.copyProperties(dto, page);
        page.setContent(fastCleanText);

        if (dto.getSourceType() != null) {
            try {
                page.setSourceType(SourceTypeEnum.valueOf(dto.getSourceType()));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("不支持的来源类型: " + dto.getSourceType());
            }
        }

        // 2. AI 状态机初始化：开启自动清洗 → 处理中(1)，否则 → 不处理(0)
        boolean needAiProcess = Integer.valueOf(1).equals(dto.getAutoOptimize());
        page.setAiProcessStatus(needAiProcess ? 1 : 0);

        // 3. 瞬间落库 MySQL，前端直接拿到 200 OK
        this.save(page);
        log.info("知识页初次录入成功，ID: {}, bookId: {}, aiStatus: {}",
                page.getId(), page.getBookId(), page.getAiProcessStatus());

        // 4. 如果开启了 AI，将任务丢入有界线程池去后台跑
        if (needAiProcess) {
            pageAsyncService.processAiIngest(page.getId(), fastCleanText, page.getBookId(), currentUserId);
        }
    }



    @Override
    public PageVO getPageById(String id) {
        Page page = getByIdWithOwnershipCheck(id);
        return convertToVO(page);
    }

    @Override
    public PageResult<PageVO> listPagesByBookId(String bookId, PageQueryDTO queryDTO) {
        String currentUserId = getCurrentUserId();

        PageHelper.startPage(queryDTO.getPage(), queryDTO.getPageSize());

        LambdaQueryWrapper<Page> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Page::getBookId, bookId)
                .inSql(Page::getBookId, ownedBookSql(currentUserId))
                .orderByDesc(Page::getCreatedAt);
        List<Page> pageList = this.list(wrapper);

        PageInfo<Page> pageInfo = new PageInfo<>(pageList);

        List<PageVO> voList = pageInfo.getList().stream()
                .map(this::convertToVO)
                .toList();

        return PageResult.<PageVO>builder()
                .total(pageInfo.getTotal())
                .page(queryDTO.getPage())
                .pageSize(queryDTO.getPageSize())
                .records(voList)
                .build();
    }

    @Override
    public void softDeletePage(String id) {
        String currentUserId = getCurrentUserId();
        LambdaUpdateWrapper<Page> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Page::getId, id)
                .inSql(Page::getBookId, ownedBookSql(currentUserId))
                .set(Page::getDeleted, 1)
                .set(Page::getDeletedAt, LocalDateTime.now());
        if (!this.update(wrapper)) {
            throw new ForbiddenException("无权操作该知识页");
        }
        log.info("知识页已移入回收站，ID: {}", id);
    }

    @Override
    public void softDeletePages(List<String> ids) {
        String currentUserId = getCurrentUserId();
        LambdaUpdateWrapper<Page> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(Page::getId, ids)
                .inSql(Page::getBookId, ownedBookSql(currentUserId))
                .set(Page::getDeleted, 1)
                .set(Page::getDeletedAt, LocalDateTime.now());
        int affected = pageMapper.update(null, wrapper);
        if (affected == 0) {
            throw new ForbiddenException("无权操作这些知识页");
        }
        if (affected < ids.size()) {
            log.warn("批量软删除部分跳过，请求: {} 条，实际: {} 条，userId: {}", ids.size(), affected, currentUserId);
        }
        log.info("批量移入回收站，userId: {}, 成功: {} 条", currentUserId, affected);
    }

    @Override
    public void permanentDeletePage(String id) {
        String currentUserId = getCurrentUserId();
        if (pageMapper.permanentDeleteById(id, currentUserId) == 0) {
            throw new ForbiddenException("无权操作该知识页");
        }
        log.info("知识页已永久删除，ID: {}", id);
    }

    @Override
    public void permanentDeletePages(List<String> ids) {
        String currentUserId = getCurrentUserId();
        int affected = pageMapper.batchPermanentDeleteByIds(ids, currentUserId);
        if (affected == 0) {
            throw new ForbiddenException("无权操作这些知识页");
        }
        if (affected < ids.size()) {
            log.warn("批量永久删除部分跳过，请求: {} 条，实际: {} 条，userId: {}", ids.size(), affected, currentUserId);
        }
        log.info("批量永久删除，userId: {}, 成功: {} 条", currentUserId, affected);
    }

    @Override
    public void restorePage(String id) {
        String currentUserId = getCurrentUserId();
        if (pageMapper.restoreById(id, currentUserId) == 0) {
            throw new ForbiddenException("无权操作该知识页");
        }
        log.info("知识页已从回收站恢复，ID: {}", id);
    }

    @Override
    public void restorePages(List<String> ids) {
        String currentUserId = getCurrentUserId();
        int affected = pageMapper.batchRestoreByIds(ids, currentUserId);
        if (affected == 0) {
            throw new ForbiddenException("无权操作这些知识页");
        }
        if (affected < ids.size()) {
            log.warn("批量恢复部分跳过，请求: {} 条，实际: {} 条，userId: {}", ids.size(), affected, currentUserId);
        }
        log.info("批量恢复，userId: {}, 成功: {} 条", currentUserId, affected);
    }

    @Override
    public PageResult<PageVO> listRecycleBin(PageQueryDTO queryDTO) {
        String currentUserId = getCurrentUserId();

        PageHelper.startPage(queryDTO.getPage(), queryDTO.getPageSize());

        List<Page> pageList = pageMapper.selectRecycleBin(currentUserId);

        PageInfo<Page> pageInfo = new PageInfo<>(pageList);

        List<PageVO> voList = pageInfo.getList().stream()
                .map(this::convertToVO)
                .toList();

        return PageResult.<PageVO>builder()
                .total(pageInfo.getTotal())
                .page(queryDTO.getPage())
                .pageSize(queryDTO.getPageSize())
                .records(voList)
                .build();
    }

    @Override
    public void emptyRecycleBin() {
        String currentUserId = getCurrentUserId();
        int count = pageMapper.permanentDeleteRecycleBin(currentUserId);
        log.info("回收站已清空，userId: {}, 共永久删除 {} 条", currentUserId, count);
    }

    @Override
    public String generateTitlePreview(String content) {
        // 1. 前置防御与清洗：如果文本太短，直接返回，别浪费大模型 Token

        if (content == null || content.trim().length() < 5) {
            log.warn("文本太短，无法生成标题");
            throw new ValidationException("文本太短，无法生成标题");
        }

        // 2. 截断超长文本（假设你的模型最多吃 2000 字，防止 Token 溢出报错）
        String safeContent = content.length() > 10000 ? content.substring(0, 10000) : content;

        // 3. 记录日志，方便未来追踪 AI 调用成本
        log.info("用户 [{}] 触发 AI 标题预览，文本长度: {}", getCurrentUserId(), safeContent.length());

        // 4. 调用大模型
        try {
            return pageAssistant.generateTitle(safeContent);
        } catch (Exception e) {
            log.warn("AI 生成标题失败", e);
            throw new ValidationException("AI 生成标题失败", e);
        }
    }


    @Override
    public String generateSummaryPreview(String content) {
        // 同理，做前置清洗、日志记录和异常拦截
        if (content == null || content.trim().length() < 10) {
            log.warn("文本太短，无法生成摘要");
            throw new ValidationException("文本太短，无法生成摘要");
        }
        String safeContent = content.length() > 3000 ? content.substring(0, 3000) : content;

        try {
            return pageAssistant.generateSummary(safeContent);
        } catch (Exception e) {
            log.warn("AI 生成摘要失败", e);
            throw new ValidationException("AI 生成摘要失败", e);
        }
    }
    // ==================== 私有方法 ====================

    private String getCurrentUserId() {
        return String.valueOf(BaseContext.getCurrentId());
    }

    /**
     * 生成子查询：当前用户拥有的所有书本 ID
     * 拼进主 SQL 的 inSql 条件里，权限校验零额外查询
     */
    private String ownedBookSql(String userId) {
        return "SELECT id FROM book WHERE user_id = '" + userId + "' AND deleted = 0";
    }

    /**
     * 查单条详情时：一条 SQL 同时做"存在性检查 + 归属检查"
     */
    private Page getByIdWithOwnershipCheck(String id) {
        String currentUserId = getCurrentUserId();
        LambdaQueryWrapper<Page> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Page::getId, id)
                .inSql(Page::getBookId, ownedBookSql(currentUserId));
        Page page = this.getOne(wrapper);
        if (page == null) {
            throw new ForbiddenException("无权访问该知识页");
        }
        return page;
    }

    private PageVO convertToVO(Page page) {
        PageVO vo = new PageVO();
        BeanUtils.copyProperties(page, vo);
        if (page.getSourceType() != null) {
            vo.setSourceType(page.getSourceType().name());
        }
        return vo;
    }
}