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

            callWithTimeout(() -> client.prompt()
                    .system(system)
                    .user(message)
                    .functions(tools.toArray(new FunctionCallback[0]))
                    .call()
                    .content(), props.getIntentTimeoutMs());

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
    static boolean looksLikeImmediateChat(String message) {
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

    private void callWithTimeout(Callable<String> call, int timeoutMs) {
        Future<String> future = null;
        try {
            future = intentExecutor.submit(call);
            if (timeoutMs > 0) {
                future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                future.get();
            }
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            log.warn("意图工具引擎超时（{}ms）", timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("意图工具引擎被中断");
        } catch (ExecutionException e) {
            throw new IllegalStateException("意图工具引擎执行失败: " + e.getCause(), e);
        }
    }
}
