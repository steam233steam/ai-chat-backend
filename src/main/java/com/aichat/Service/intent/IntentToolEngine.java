package com.aichat.Service.intent;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.aichat.Config.IntentExecutorConfig;
import com.aichat.Config.MindButlerProperties;
import com.aichat.Config.ModelRouter;
import com.aichat.Dto.IntentExtractionResult;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.TaskCandidate;
import com.aichat.utils.IntentDetector;

/**
 * 意图抽取的工具调用引擎。
 *
 * <p>与旧的「一个大提示词 + 固定 JSON」相比，这里让模型直接调工具：工具名、参数、
 * 说明都来自真实能力清单，所以新增一个 MCP 服务器不需要改提示词，也不需要改解析代码。
 *
 * <p>模型调完工具后，参数落在 {@link IntentPlan} 里；本类把它转成 {@code IntentExtractionResult}，
 * 下游的「校验 → 待确认 → 落库」链路完全不变。
 *
 * <p>S0 阶段它只作影子通道：评测里和新旧引擎对比，生产链路仍走 {@code app.intent.engine=prompt}。
 */
@Component
public class IntentToolEngine {

    private static final Logger log = LoggerFactory.getLogger(IntentToolEngine.class);
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ModelRouter modelRouter;
    private final MindButlerProperties props;
    private final IntentToolCatalog catalog;
    private final Clock clock;
    private final ExecutorService intentExecutor;

    public IntentToolEngine(ModelRouter modelRouter, MindButlerProperties props,
            IntentToolCatalog catalog, Clock clock,
            @Qualifier(IntentExecutorConfig.INTENT_EXECUTOR) ExecutorService intentExecutor) {
        this.modelRouter = modelRouter;
        this.props = props;
        this.catalog = catalog;
        this.clock = clock;
        this.intentExecutor = intentExecutor;
    }

    /**
     * 抽取一句话里的记忆与任务。
     *
     * <p>任何异常都返回空候选（与旧引擎一致）：意图识别是可选的增强，
     * 失败时上层走固定兜底，不该让整轮对话 500。
     */
    public IntentExtractionResult extract(IntentDetector.Intent intent, String message) {
        // 两处省事又防误判：
        // 1) QUERY 是「我有哪些任务/你记住了我什么」，直接读库回答，不该再走模型；
        // 2) 没有触发词的句子，如果本身是问句或「现在就要答案」的请求（现在几点、
        //    明天天气怎么样、帮我翻译这句），交给聊天直接回答，否则模型会把它建成定时任务。
        if (intent == IntentDetector.Intent.QUERY || looksLikeImmediateChat(message)) {
            log.debug("意图引擎跳过（intent={}）: {}", intent, message);
            return new IntentExtractionResult(null, null);
        }
        try {
            String model = modelRouter.resolve(props.getIntentExtractModel());
            ChatClient client = modelRouter.client(model);
            IntentPlan plan = new IntentPlan();
            List<FunctionCallback> tools = catalog.build(plan);
            String system = buildSystemPrompt(intent);

            // 注意：不要用 .options(ChatOptions) 覆盖默认选项——M5 里它会把工具调用配置一起换掉，
            // 表现为「模型一次都不调工具」。抽取的确定性靠提示词与兜底，不靠 temperature。
            FunctionCallback[] callbacks = tools.toArray(new FunctionCallback[0]);
            boolean finished = callWithTimeout(() -> client.prompt()
                    .system(system)
                    .user(message)
                    .functions(callbacks)
                    .call()
                    .content(), props.getIntentTimeoutMs());

            // 模型偶尔会「漏调」。再调一次模型会把延迟翻倍、还容易撞超时，
            // 所以这里用确定性兜底：句子里有明显的「提醒/记得/待办」信号却什么都没抽到时，
            // 直接按原话兜一条待办，宁可让用户改标题，也不静默丢掉。
            if (finished && plan.isEmpty()) {
                planRescue(plan, message, intent);
            }

            log.info("意图工具引擎: 模型={}, 工具调用={}, 记忆={}, 任务={}",
                    model, plan.getCalls(), plan.getMemories().size(), plan.getTasks().size());
            return new IntentExtractionResult(plan.getMemories(), plan.getTasks());
        } catch (RuntimeException e) {
            log.warn("意图工具引擎失败，返回空候选: {}", e.getMessage());
            return new IntentExtractionResult(null, null);
        }
    }

    private static final java.util.regex.Pattern IMMEDIATE_CHAT = java.util.regex.Pattern.compile(
            "[?？]|吗|呢|怎么样|为什么|什么|几点|多久|多少|在哪|哪里|谁|是不是|能不能|可不可以"
                    + "|翻译|解释一下|解释下|总结一下|润色|改写|算一下");

    /** 「现在就想知道答案」的句子：问句，或让我立刻做一次文字处理。 */
    public static boolean looksLikeImmediateChat(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return false;
        }
        // 「以后才做」的信号优先：带这些词就还是交给模型判断
        if (text.matches(".*(提醒我|记得|待办|每天|每周|每月|到点|别忘了|回头).*")) {
            return false;
        }
        return IMMEDIATE_CHAT.matcher(text).find();
    }

    private static final java.util.regex.Pattern TODO_SIGNAL = java.util.regex.Pattern.compile(
            "提醒我|提醒一下|记得|别忘了|帮我记|记一下|待办|回头|别忘了|要交|该交|要去做");

    private static final java.util.regex.Pattern TIME_WORDS = java.util.regex.Pattern.compile(
            "今天|明天|后天|今晚|明早|周[一二三四五六日天]|下周|下个?月|每天|每周|每月|\\d+点|\\d+号|\\d+日|早上|上午|中午|下午|晚上");

    /** 只在有明确「以后要做」信号时兜底；问句和闲聊不兜。 */
    static boolean looksLikeTodo(String message, IntentDetector.Intent intent) {
        String text = message == null ? "" : message;
        if (intent == IntentDetector.Intent.QUERY || looksLikeImmediateChat(text)) {
            return false;
        }
        return TODO_SIGNAL.matcher(text).find();
    }

    /** 把「明天八点提醒我交作业」压成「交作业」这种能当标题的短语。 */
    static String rescueTitle(String message) {
        String title = message == null ? "" : message;
        title = title.replaceAll("^(麻烦|帮我|请|那个)?(一下，?)?", "");
        title = title.replaceAll("(提醒我|提醒一下|记得|别忘了|帮我记|记一下|待办)", "");
        title = title.replaceAll(TIME_WORDS.pattern(), "");
        title = title.replaceAll("[，,。！!？?、；;：:\\s]+", " ").trim();
        if (title.isEmpty()) {
            title = message == null ? "待办" : message.trim();
        }
        return title.length() > 40 ? title.substring(0, 40) : title;
    }

    /**
     * 模型没调工具时的确定性兜底。
     *
     * <p>事实证明「模型这次没调」是常态而不是异常，靠提示词求它每次都调不现实。
     * 这里只处理信号明确的三种句式：定时查东西、提醒我做某事、让我记住某事；
     * 其余（含糊的、闲聊的）仍然交给聊天，避免乱建。
     */
    void planRescue(IntentPlan plan, String message, IntentDetector.Intent intent) {
        String text = message == null ? "" : message;
        if (intent == IntentDetector.Intent.QUERY || looksLikeImmediateChat(text)) {
            return;
        }
        TaskCandidate task = null;
        if (looksLikeScheduledQuery(text)) {
            task = new TaskCandidate();
            task.setTitle(rescueTitle(text));
            task.setRecurrence(TIME_WORDS.matcher(text).find() && text.contains("每天") ? "daily" : "once");
            task.setAction("mcp");
            boolean weather = text.contains("天气") || text.contains("气温") || text.contains("下雨");
            task.setTool(weather ? "weather" : "search");
            task.setToolArgs(weather ? java.util.Map.of("city", cityOf(text))
                    : java.util.Map.of("q", queryOf(text)));
        } else if (looksLikeTodo(text, intent)) {
            task = new TaskCandidate();
            task.setTitle(rescueTitle(text));
            task.setRecurrence("once");
            task.setAction("create");
        }
        if (task != null) {
            plan.addTask(task);
            plan.record("rescue");
            log.info("意图引擎兜底建任务: {}（{}）", task.getTitle(), task.getAction());
            return;
        }
        if (MEMORY_SIGNAL.matcher(text).find()) {
            MemoryCandidate memory = new MemoryCandidate();
            memory.setKey(text.contains("叫我") || text.contains("称呼") ? "relation" : "fact");
            memory.setValue(text.replaceAll("^(请|麻烦)?(帮我)?(记住|记一下|记下|记着)[，,：:]?", "").trim());
            memory.setMemType("stable");
            memory.setScope("global");
            memory.setConfidence(0.8);
            if (!memory.getValue().isBlank()) {
                plan.addMemory(memory);
                plan.record("rescue");
                log.info("意图引擎兜底记记忆: {}", memory.getValue());
            }
        }
    }

    private static final java.util.regex.Pattern QUERY_SIGNAL = java.util.regex.Pattern.compile(
            "查一下|查下|搜一下|搜下|看看|告诉我|播报|推给我|发我|推送");
    private static final java.util.regex.Pattern MEMORY_SIGNAL = java.util.regex.Pattern.compile(
            "记住|记一下|记下|记着");

    /** 「每天/明天 + 查/搜/告诉我」这类定时取数的句子，模型经常漏调。 */
    static boolean looksLikeScheduledQuery(String message) {
        String text = message == null ? "" : message;
        return TIME_WORDS.matcher(text).find() && QUERY_SIGNAL.matcher(text).find();
    }

    static String cityOf(String message) {
        // 先去掉「查一下/看看」这类动作词，否则「查一下北京天气」会把「一下北京」当成城市
        String text = message == null ? "" : message.replaceAll(
                "(查一下|查下|搜一下|搜下|告诉我|播报|推给我|发我|推送|看看|一下)", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5]{2,4}?)(?:市|区)?(?:的)?(?:天气|气温)").matcher(text);
        return m.find() ? m.group(1) : "北京";
    }

    static String queryOf(String message) {
        String q = message.replaceAll(TIME_WORDS.pattern(), "")
                .replaceAll("(查一下|查下|搜一下|搜下|告诉我|播报|推给我|发我|推送|看看|一下)", "")
                .replaceAll("[，,。！!？?、；;：:\\s]+", " ").trim();
        return q.isBlank() ? message : q;
    }

    String buildSystemPrompt(IntentDetector.Intent intent) {
        LocalDateTime now = LocalDateTime.now(clock);
        StringBuilder sb = new StringBuilder();
        sb.append("你是个人助理的意图识别器。根据用户这一句话，调用合适的工具把他说的事情变成结构化条目。\n\n");
        sb.append("当前时间：").append(HUMAN.format(now)).append("（时区 Asia/Shanghai，星期")
                .append("一二三四五六日".charAt(now.getDayOfWeek().getValue() - 1)).append("）\n\n");
        sb.append("规则：\n");
        sb.append("1. 只处理用户明确表达的事。闲聊、提问、寒暄一律不调工具。\n");
        sb.append("2. 一句话里有多件事就连续调多个工具：既要记住偏好、又要定时间，就 remember + create_task 都调。\n");
        sb.append("3. 带时间的安排（明天九点、下周三、每天、每周五）只调 create_task，不要用 remember 存成记忆。\n");
        sb.append("4. 时间按上面的当前时间推算绝对时间，输出 ISO-8601（如 2026-11-11T09:00:00）；只说日期没说时刻时默认 09:00。\n");
        sb.append("5. 任务标题是动宾短语，不带时间词：用户说「明天九点交作业」，标题就是「交作业」。\n");
        sb.append("6. 用户要「查点什么再发给我」时，建任务并把 action 设为 mcp、tool 填工具短名、toolArgs 填参数。\n");
        sb.append("7. 不要编造用户没说的内容，但也不要因为拿不准就放弃：能确定多少填多少，其余留空。\n");
        sb.append("8. 用户让你「以后要做」一件事但没给时间（买牛奶、交电费、给谁打电话）→ 照样调 create_task，dueAt 不填，它会变成待办。\n");
        sb.append("9. 「现在就想知道答案」的问句不要建任务：句子里有「吗/呢/怎么样/多少/是什么」或结尾是问号，且没有「提醒/记得/帮我/到点」这类要求时，一律不调工具。\n");
        sb.append("10. 判断标准：这件事是不是「换个时间也得做」？是就建任务，只是想马上知道答案就不建。\n");
        sb.append("11. action=mcp 时 tool 只能填这四个短名之一：now、weather、search、fetch_url；没有对应工具就留空走站内提醒。\n");
        if (intent == IntentDetector.Intent.NONE) {
            sb.append("\n这一句可能什么都不用做——那就不调工具，直接结束。\n");
        }
        return sb.toString();
    }

    /** @return 是否在超时前完成（超时/中断返回 false，用于决定要不要重试） */
    private boolean callWithTimeout(Callable<String> call, int timeoutMs) {
        Future<String> future = null;
        try {
            future = intentExecutor.submit(call);
            if (timeoutMs > 0) {
                future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                future.get();
            }
            return true;
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            log.warn("意图工具引擎超时（{}ms）", timeoutMs);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("意图工具引擎被中断");
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException("意图工具引擎执行失败: " + e.getCause(), e);
        }
    }
}
