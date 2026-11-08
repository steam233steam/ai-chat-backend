package com.aichat.Service.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.aichat.Dto.MemoryVO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 「改/删哪一条？」追问的上下文。
 *
 * 之前追问完就丢了候选，用户下一句「考公的那个」没有触发词、也没有上下文，
 * 会被当成闲聊回一段人设文案。这里把候选存 5 分钟，让下一句能接上。
 *
 * 存 Redis 而不是内存：进程重启、多实例部署都不会丢，也不用清理。
 */
@Component
public class MemoryChangeClarifyStore {
    private static final Logger log = LoggerFactory.getLogger(MemoryChangeClarifyStore.class);

    private static final String KEY_PREFIX = "aichat:intent:clarify:";
    private static final Duration TTL = Duration.ofMinutes(5);

    /** 等用户挑目标。 */
    public static final String STAGE_PICK = "PICK";
    /** 已挑中 UPDATE 目标，等用户给新值。 */
    public static final String STAGE_VALUE = "VALUE";

    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_UPDATE = "UPDATE";

    /** 候选记忆的快照（只留选条需要的字段）。 */
    public record Candidate(long id, String memKey, String content) {
    }

    /** action + 候选；STAGE_VALUE 时 targetId/targetContent 有值。 */
    public record State(String action, List<Candidate> candidates, Long targetId, String targetContent) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public MemoryChangeClarifyStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** 追问「改哪一条」时存候选。 */
    public void savePick(String userId, String sessionId, String action, List<MemoryVO> candidates) {
        save(userId, sessionId, new State(action, toCandidates(candidates), null, null));
    }

    /** 已选好目标、缺新值时存目标；stage 由 targetId 是否为空推导。 */
    public void saveValue(String userId, String sessionId, long targetId, String targetContent) {
        save(userId, sessionId, new State(ACTION_UPDATE, List.of(), targetId, targetContent));
    }

    public State load(String userId, String sessionId) {
        try {
            String json = redis.opsForValue().get(key(userId, sessionId));
            if (json == null || json.isBlank()) {
                return null;
            }
            return mapper.readValue(json, State.class);
        } catch (Exception e) {
            log.warn("读取记忆澄清状态失败（{}），按无状态处理", e.getMessage());
            return null;
        }
    }

    public void clear(String userId, String sessionId) {
        try {
            redis.delete(key(userId, sessionId));
        } catch (Exception e) {
            log.warn("清理记忆澄清状态失败（{}）", e.getMessage());
        }
    }

    /** 当前状态处于哪个阶段。 */
    public static String stageOf(State state) {
        return state.targetId() == null ? STAGE_PICK : STAGE_VALUE;
    }

    private void save(String userId, String sessionId, State state) {
        try {
            redis.opsForValue().set(key(userId, sessionId), mapper.writeValueAsString(state), TTL);
        } catch (Exception e) {
            log.warn("保存记忆澄清状态失败（{}）", e.getMessage());
        }
    }

    private static List<Candidate> toCandidates(List<MemoryVO> memories) {
        List<Candidate> list = new ArrayList<>();
        if (memories == null) {
            return list;
        }
        for (MemoryVO m : memories) {
            if (m != null && m.getId() != null) {
                list.add(new Candidate(m.getId(), m.getMemKey(), m.getContent()));
            }
        }
        return list;
    }

    private static String key(String userId, String sessionId) {
        return KEY_PREFIX + userId + ":" + sessionId;
    }
}
