package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.SessionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理服务
 * 优先使用 Redis 持久化会话，Redis 不可用时降级到本地 ConcurrentHashMap
 */
@Service
public class SessionService {

    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);

    private static final String SESSION_KEY_PREFIX = "session:";
    private static final long SESSION_TTL_SECONDS = 86400; // 24小时

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /** Redis 不可用时的本地 fallback */
    private final Map<String, SessionInfo> localFallback = new ConcurrentHashMap<>();

    /**
     * 获取或创建会话
     */
    public SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        // 优先从 Redis 读取
        if (isRedisAvailable()) {
            try {
                String json = stringRedisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
                if (json != null && !json.isEmpty()) {
                    SessionInfo session = objectMapper.readValue(json, SessionInfo.class);
                    logger.debug("从 Redis 恢复会话: {}", sessionId);
                    return session;
                }
            } catch (Exception e) {
                logger.warn("从 Redis 读取会话失败，降级到本地存储: {}", e.getMessage());
            }
        }

        // Redis 未命中或不可用，创建新会话（同时尝试写入 Redis）
        SessionInfo session = new SessionInfo(sessionId);
        saveSession(sessionId, session);
        return session;
    }

    /**
     * 保存会话到 Redis（带降级）
     */
    public void saveSession(String sessionId, SessionInfo sessionInfo) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        if (isRedisAvailable()) {
            try {
                String json = objectMapper.writeValueAsString(sessionInfo);
                stringRedisTemplate.opsForValue().set(
                        SESSION_KEY_PREFIX + sessionId,
                        json,
                        java.time.Duration.ofSeconds(SESSION_TTL_SECONDS)
                );
                logger.debug("会话已保存到 Redis: {}", sessionId);
                return;
            } catch (Exception e) {
                logger.warn("Redis 保存会话失败，使用本地 fallback: {}", e.getMessage());
            }
        }

        // Fallback 到本地内存
        localFallback.put(sessionId, sessionInfo);
        logger.debug("会话已保存到本地内存: {}", sessionId);
    }

    /**
     * 删除会话
     */
    public void deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        if (isRedisAvailable()) {
            try {
                stringRedisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
                logger.debug("已从 Redis 删除会话: {}", sessionId);
            } catch (Exception e) {
                logger.warn("Redis 删除会话失败: {}", e.getMessage());
            }
        }

        localFallback.remove(sessionId);
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
