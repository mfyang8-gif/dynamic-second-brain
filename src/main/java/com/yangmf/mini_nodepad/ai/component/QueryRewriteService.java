package com.yangmf.mini_nodepad.ai.component;

import com.yangmf.mini_nodepad.ai.aiservice.QueryRewriteAssistant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final QueryRewriteAssistant queryRewriteAssistant;

    public RewrittenQuery rewrite(String rawQuery, String conversationContext) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new RewrittenQuery(rawQuery, List.of(), false);
        }

        if (!needsRewrite(rawQuery)) {
            return new RewrittenQuery(rawQuery, List.of(rawQuery), false);
        }

        try {
            String rewriteResult = queryRewriteAssistant.rewrite(rawQuery, conversationContext);
            return parseRewriteResult(rewriteResult, rawQuery);
        } catch (Exception e) {
            log.warn("Query 改写失败，降级使用原始问题 | query={}", rawQuery, e);
            return new RewrittenQuery(rawQuery, List.of(rawQuery), false);
        }
    }

    private boolean needsRewrite(String query) {
        String pronouns = "它|他|她|这个|那个|那里|前面|上面|刚才|之前";
        if (query.matches(".*(" + pronouns + ").*")) {
            return true;
        }
        if (query.length() < 5) {
            return true;
        }
        if (query.contains("？") || query.contains("?")) {
            String[] clauses = query.split("[，,；;]");
            if (clauses.length >= 3) {
                return true;
            }
        }
        return false;
    }

    private RewrittenQuery parseRewriteResult(String rewriteResult, String rawQuery) {
        if (rewriteResult == null || rewriteResult.isBlank()) {
            return new RewrittenQuery(rawQuery, List.of(rawQuery), false);
        }

        String[] lines = rewriteResult.strip().split("\n");
        String rewrittenQuery = lines[0].replaceFirst("^(改写|主查询)[：:]", "").trim();
        
        List<String> subQueries = List.of();
        if (lines.length > 1) {
            subQueries = java.util.Arrays.stream(lines)
                    .skip(1)
                    .filter(l -> l.matches("^\\d+[.、].*"))
                    .map(l -> l.replaceFirst("^\\d+[.、]\\s*", ""))
                    .filter(l -> !l.isBlank())
                    .limit(3)
                    .toList();
        }

        boolean decomposed = subQueries.size() > 1;
        return new RewrittenQuery(
                rewrittenQuery.isEmpty() ? rawQuery : rewrittenQuery,
                decomposed ? subQueries : List.of(rewrittenQuery.isEmpty() ? rawQuery : rewrittenQuery),
                decomposed
        );
    }

    public record RewrittenQuery(
            String primaryQuery,
            List<String> searchQueries,
            boolean decomposed
    ) {}
}