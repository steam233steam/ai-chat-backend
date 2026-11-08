package com.aichat.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.aichat.Dto.MemoryVO;

/**
 * 把「把目标删掉」「把风格改成简洁一点」这类口语，解析成一条可确认的记忆变更提案。
 *
 * 这里刻意不用 LLM：
 * - 记忆条数有限（注入上限 5 + 5），规则匹配足够；
 * - 删除是不可逆操作，规则命中不了就追问，宁可多问一句也不要猜错人；
 * - 纯函数好测，也省一次第三方调用。
 *
 * 命中不了就返回 clarify 文案，由上层直接回复用户，不产生 pending。
 */
public final class MemoryChangeParser {

    private static final List<String> DELETE_WORDS = List.of(
            "删掉", "删除", "删了", "去掉", "移除", "清掉", "忘掉", "忘记", "不要再记");

    private static final List<String> UPDATE_WORDS = List.of(
            "改成", "改为", "换成", "更新为", "改作", "改一下", "改个");

    /** mem_key 的口语别名：先按 key 缩小范围，命中不了再按内容匹配。 */
    private static final List<String[]> KEY_ALIASES = List.of(
            new String[] {"style", "风格", "偏好", "喜好", "语气", "回答方式"},
            new String[] {"goal", "目标", "计划", "打算"},
            new String[] {"schedule", "作息", "日程", "时间安排", "提醒时间"},
            new String[] {"relation", "关系", "称呼", "昵称"});

    private MemoryChangeParser() {
    }

    /**
     * 解析结果：要么给出提案，要么给出一句要追问用户的话。
     *
     * candidates 只在“候选不止一条、需要用户挑”时有值：上层把它存进 Redis，
     * 用户下一句「考公的那个」「第二条」才能接得上，不然那句会被当成闲聊。
     */
    public record Resolution(Plan plan, String clarify, List<MemoryVO> candidates) {

        static Resolution of(Plan plan) {
            return new Resolution(plan, null, List.of());
        }

        static Resolution ask(String clarify) {
            return new Resolution(null, clarify, List.of());
        }

        static Resolution ask(String clarify, List<MemoryVO> candidates) {
            return new Resolution(null, clarify, candidates == null ? List.of() : List.copyOf(candidates));
        }

        public boolean resolved() {
            return plan != null;
        }
    }

    /** 一条待确认的变更：targets 可能多条（同一 key 下的重复记忆），after 仅 UPDATE 时有值。 */
    public record Plan(Action action, List<MemoryVO> targets, String after, String preview) {

        public boolean isDelete() {
            return action == Action.DELETE;
        }
    }

    public enum Action {
        DELETE, UPDATE
    }

    /**
     * @param message 用户原话
     * @param memories 当前用户所有生效记忆（调用方负责限制条数）
     */
    public static Resolution parse(String message, List<MemoryVO> memories) {
        if (message == null || message.isBlank() || memories == null || memories.isEmpty()) {
            return Resolution.ask(NO_TARGET);
        }

        boolean delete = containsAny(message, DELETE_WORDS);
        String updateWord = firstContaining(message, UPDATE_WORDS);
        if (!delete && updateWord == null) {
            return Resolution.ask(NO_TARGET);
        }

        Scoped scoped = narrowByKey(message, memories);
        boolean keyHit = scoped.keyHit();
        List<MemoryVO> pool = scoped.memories();
        List<MemoryVO> matched;
        if (keyHit && pool.size() == 1) {
            matched = pool;
        } else {
            matched = matchByContent(message, pool);
            if (matched.isEmpty() && keyHit) {
                // 用户点名了 mem_key（"把目标删掉"），但内容里没有交集：
                // 该 key 下只有一条就是它，多条就让用户选，总之不能落到"没找到"
                matched = pool;
            }
        }

        if (matched.isEmpty()) {
            return Resolution.ask(NO_TARGET);
        }
        if (matched.size() > 1) {
            return Resolution.ask(ambiguous(matched), matched);
        }

        MemoryVO target = matched.get(0);
        if (delete) {
            return Resolution.of(new Plan(Action.DELETE, List.of(target), null,
                    "要删掉这条记忆：" + describe(target) + "。删掉后我不会再拿它回答你。"));
        }

        String after = extractAfter(message, updateWord);
        if (after.isBlank()) {
            return Resolution.ask("你想把「" + target.getContent() + "」改成什么？例如「把"
                    + keyLabel(target.getMemKey()) + "改成……」。");
        }
        if (after.equals(target.getContent())) {
            return Resolution.ask("改完和现在一样，我就先不动了。要改成别的内容可以再说一次。");
        }
        return Resolution.of(new Plan(Action.UPDATE, List.of(target), after,
                "要把这条记忆改成：「" + after + "」。原来是：" + describe(target) + "。"));
    }

    /**
     * 这句话是不是在说“改任务”（改时间、删任务）。
     *
     * 任务的时间语义比记忆复杂（重复规则、时区、已完成状态），聊天里改任务这轮还没做；
     * 用它把用户引导到任务列表，比含糊地回“没找到记忆”清楚。
     */
    private static final List<String> TASK_WORDS = List.of(
            "任务", "提醒", "待办", "延后", "推迟", "提前", "换个时间", "改时间", "改到");

    /** 用户话里是否点名了某个记忆类别（决定走记忆变更还是任务引导）。 */
    public static boolean mentionsMemoryKey(String message) {
        if (message == null) {
            return false;
        }
        for (String[] alias : KEY_ALIASES) {
            for (int i = 1; i < alias.length; i++) {
                if (message.contains(alias[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 用户话里是否有任务相关词（且没点名记忆类别）。 */
    public static boolean looksLikeTaskChange(String message) {
        if (message == null || mentionsMemoryKey(message)) {
            return false;
        }
        for (String w : TASK_WORDS) {
            if (message.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 这句话带没带“改/删记忆”的触发词（用来判断是不是换了个新诉求）。 */
    public static boolean looksLikeChangeRequest(String message) {
        return actionOf(message) != null;
    }

    /** 从话里判定意图动作：删除优先（「删掉改成…」这种以删为准），都没有返回 null。 */
    public static Action actionOf(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        if (containsAny(message, DELETE_WORDS)) {
            return Action.DELETE;
        }
        return firstContaining(message, UPDATE_WORDS) == null ? null : Action.UPDATE;
    }

    /**
     * 从一串候选里挑出用户指的是哪几条，返回下标。
     *
     * 支持两种说法：「第二条」这种序数，以及「考公的那个」这种带内容关键词的。
     * 关键词走和选条同一个 overlap（按字符集合、>=2 才算），并列最高分就都返回，
     * 由上层决定是继续追问还是命中。
     */
    public static List<Integer> pickCandidateIndexes(String message, List<String> contents) {
        if (message == null || message.isBlank() || contents == null || contents.isEmpty()) {
            return List.of();
        }
        int ordinal = ordinalIndex(message);
        if (ordinal >= 0) {
            return ordinal < contents.size() ? List.of(ordinal) : List.of();
        }
        int best = 0;
        List<Integer> top = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            int score = overlap(message, contents.get(i));
            if (score > best) {
                best = score;
                top.clear();
                top.add(i);
            } else if (score == best && score > 0) {
                top.add(i);
            }
        }
        return best >= 2 ? top : List.of();
    }

    /** 「第一条」「第 2 个」「第三项」→ 0 基下标；没有序数返回 -1。 */
    private static int ordinalIndex(String message) {
        java.util.regex.Matcher m = ORDINAL.matcher(message);
        if (!m.find()) {
            return -1;
        }
        String token = m.group(1);
        int chinese = "一二三四五六七八九十".indexOf(token);
        if (chinese >= 0) {
            return chinese;
        }
        try {
            return Integer.parseInt(token) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final java.util.regex.Pattern ORDINAL =
            java.util.regex.Pattern.compile("第\\s*([0-9一二三四五六七八九十])\\s*(条|个|项)?");

    /**
     * 澄清阶段拿到的新值：整句就是用户想改成的内容，只做口语尾巴清理。
     * 和 extractAfter 的区别是不依赖"改成"这类触发词——用户可能只回一句「明年考公」。
     */
    public static String extractAfterLoose(String message) {
        if (message == null) {
            return "";
        }
        String rest = message.trim().replaceFirst("^[：:，,。\\s]+", "");
        rest = rest.replaceFirst("[。！!？?吧呀啊哈\\s]+$", "");
        return rest.length() > 200 ? rest.substring(0, 200) : rest;
    }

    /**
     * 澄清阶段带触发词的写法（「考公那个改成明年」）：按触发词切出真正的新值，
     * 否则会把「考公那个改成」一起当成新内容。
     */
    public static String extractAfterByTrigger(String message) {
        String trigger = firstContaining(message, UPDATE_WORDS);
        return trigger == null ? "" : extractAfter(message, trigger);
    }

    private static final String NO_TARGET =
            "我没找到要改的记忆。你可以说得具体点，比如「把我关于作息的记忆删掉」。";

    /** 按 key 缩小范围的结果；keyHit 表示用户话里点了 mem_key（决定"多说一句"还是"直接问哪条"）。 */
    private record Scoped(List<MemoryVO> memories, boolean keyHit) {
    }

    private static Scoped narrowByKey(String message, List<MemoryVO> memories) {
        for (String[] alias : KEY_ALIASES) {
            String key = alias[0];
            boolean hit = false;
            for (int i = 1; i < alias.length; i++) {
                if (message.contains(alias[i])) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            List<MemoryVO> sameKey = new ArrayList<>();
            for (MemoryVO m : memories) {
                if (key.equals(m.getMemKey())) {
                    sameKey.add(m);
                }
            }
            if (!sameKey.isEmpty()) {
                return new Scoped(sameKey, true);
            }
        }
        return new Scoped(memories, false);
    }

    /** 按「消息里出现了这条记忆的多少个字」打分，只接受唯一最高分。 */
    private static List<MemoryVO> matchByContent(String message, List<MemoryVO> memories) {
        int best = 0;
        List<MemoryVO> top = new ArrayList<>();
        for (MemoryVO m : memories) {
            int score = overlap(message, m.getContent());
            if (score > best) {
                best = score;
                top.clear();
                top.add(m);
            } else if (score == best && score > 0) {
                top.add(m);
            }
        }
        // 单条命中太弱（只沾到一个字）时宁可追问：删除不可逆，猜错代价大
        return best >= 2 ? top : List.of();
    }

    /**
     * 参与"内容相似度"打分时要忽略的词。
     *
     * 没有这一步，「把目标的记忆删掉」里的"记忆"会跟「验收-隔离记忆」匹配上 2 个字，
     * 于是在目标类有两条记忆时误判成唯一命中。这些词说明的是"我要改哪一类"，
     * 不是"改哪一条"，不该参与选条。
     */
    private static final List<String> SCORE_STOPWORDS = List.of(
            "记忆", "目标", "风格", "偏好", "喜好", "作息", "日程", "关系", "称呼", "昵称",
            "这条", "那条", "这个", "那个", "一下", "一条");

    private static String scrub(String message) {
        String cleaned = message;
        for (String w : SCORE_STOPWORDS) {
            cleaned = cleaned.replace(w, " ");
        }
        return cleaned;
    }

    private static int overlap(String message, String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        String cleaned = scrub(message);
        Set<Character> chars = new LinkedHashSet<>();
        for (char c : cleaned.toCharArray()) {
            chars.add(c);
        }
        int hit = 0;
        for (char c : content.toCharArray()) {
            if (!isMeaningful(c)) {
                continue;
            }
            if (chars.contains(c)) {
                hit++;
            }
        }
        return hit;
    }

    private static boolean isMeaningful(char c) {
        return Character.isLetterOrDigit(c);
    }

    private static String extractAfter(String message, String trigger) {
        int idx = message.indexOf(trigger);
        if (idx < 0) {
            return "";
        }
        String rest = message.substring(idx + trigger.length()).trim();
        // 「改成：xxx」「改成 xxx 吧」这类口语尾巴
        rest = rest.replaceFirst("^[：:，,。\\s]+", "");
        rest = rest.replaceFirst("[。！!？?吧呀啊哈\\s]+$", "");
        return rest.length() > 200 ? rest.substring(0, 200) : rest;
    }

    private static String ambiguous(List<MemoryVO> matched) {
        List<String> parts = new ArrayList<>();
        int limit = Math.min(matched.size(), 4);
        for (int i = 0; i < limit; i++) {
            parts.add("「" + matched.get(i).getContent() + "」");
        }
        return "我找到 " + matched.size() + " 条可能的记忆：" + String.join("、", parts)
                + "。你想改哪一条？可以说得更具体一点。";
    }

    private static String describe(MemoryVO m) {
        return keyLabel(m.getMemKey()) + " · " + m.getContent();
    }

    private static String keyLabel(String memKey) {
        if (memKey == null) {
            return "记忆";
        }
        return switch (memKey) {
            case "style" -> "风格偏好";
            case "goal" -> "目标";
            case "schedule" -> "作息";
            case "relation" -> "关系称呼";
            default -> memKey;
        };
    }

    private static boolean containsAny(String message, List<String> words) {
        return firstContaining(message, words) != null;
    }

    private static String firstContaining(String message, List<String> words) {
        String lower = message.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (lower.contains(w)) {
                return w;
            }
        }
        return null;
    }
}
