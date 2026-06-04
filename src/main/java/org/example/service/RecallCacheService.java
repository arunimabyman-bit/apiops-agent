package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;

/**
 * 多路召回缓存服务
 * 以查询文本的 MD5 hash 为 key 缓存 Milvus 向量搜索结果到 Redis，
 * 减少重复的 Embedding + Milvus 查询开销
 */
@Service
public class RecallCacheService {

    private static final Logger logger = LoggerFactory.getLogger(RecallCacheService.class);

    private static final String RECALL_KEY_PREFIX = "recall:";
    private static final long RECALL_TTL_SECONDS = 3600; // 1小时

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试从缓存获取召回结果
     *
     * @param query 查询文本
     * @param topK  返回数量
     * @return 缓存的搜索结果列表，未命中返回 null
     */
    public List<VectorSearchService.SearchResult> tryGet(String query, int topK) {
        if (!isRedisAvailable()) {
            return null;
        }

        try {
            String key = buildCacheKey(query, topK);
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null && !json.isEmpty()) {
                List<VectorSearchService.SearchResult> results =
                        objectMapper.readValue(json, new TypeReference<List<VectorSearchService.SearchResult>>() {});
                logger.info("召回缓存命中, query hash: {}, 结果数: {}", md5Short(query), results.size());
                return results;
            }
        } catch (Exception e) {
            logger.warn("召回缓存读取失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将召回结果写入缓存
     *
     * @param query   查询文本
     * @param topK    返回数量
     * @param results 搜索结果列表
     */
    public void cachePut(String query, int topK, List<VectorSearchService.SearchResult> results) {
        if (!isRedisAvailable() || results == null || results.isEmpty()) {
            return;
        }

        try {
            String key = buildCacheKey(query, topK);
            String json = objectMapper.writeValueAsString(results);
            stringRedisTemplate.opsForValue().set(key, json, Duration.ofSeconds(RECALL_TTL_SECONDS));
            logger.info("召回结果已缓存, query hash: {}, 结果数: {}, TTL: 1h",
                    md5Short(query), results.size());
        } catch (Exception e) {
            logger.warn("召回缓存写入失败: {}", e.getMessage());
        }
    }

    /**
     * 构建缓存 key: recall:{md5_hash}:{topK}
     */
    private String buildCacheKey(String query, int topK) {
        return RECALL_KEY_PREFIX + md5Short(query) + ":" + topK;
    }

    /**
     * 计算 MD5 前 16 位的简短 hash
     */
    static String md5Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 检查 Redis 是否可用
     */
    private boolean isRedisAvailable() {
        if (stringRedisTemplate == null) {
            return false;
        }
        try {
            stringRedisTemplate.opsForValue().get("_health_check_");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
