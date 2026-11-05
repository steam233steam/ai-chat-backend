package com.aichat.utils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务能力边界（P1）。
 *
 * <p>P1 只做“应用内任务记录”，没有 QQ / 微信 / 短信 / 邮件 / 电话等外部推送通道，
 * 也不能替用户完成下单、支付、转账等真实世界操作。这里用确定性规则在调用 LLM 提取前
 * 拦下这类请求，避免生成一个“看起来会提醒、实际不会发生”的假任务。
 *
 * <p>后续接入外部通道时，应把这里升级成能力注册表（channel -> adapter），
 * 而不是继续在 prompt 里让模型自由承诺。
 */
public final class TaskCapabilityGuard {

    /** 只有“用/在/通过某通道提醒/通知/发给我”才算通道请求，避免“用QQ邮箱登录/查邮件”被误伤。 */
    private static final Pattern QQ = Pattern.compile(
            "(?:在|用|通过|走|发到)\\s*(?:qq|扣扣|企鹅)(?:上|里|中|群|号|群里)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|(?:qq|扣扣|企鹅)(?:上|里|中|群|号|群里)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|发(?:qq|扣扣)"
                    + "|(?:qq|扣扣|企鹅)(?:提醒|通知)我");
    private static final Pattern WECHAT = Pattern.compile(
            "(?:在|用|通过|走|发到)\\s*(?:微信|wechat|weixin)(?:上|里|中|群|号|群里)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|发微信"
                    + "|(?:微信|wechat|weixin)(?:上|里|中|群|号|群里)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|(?:微信|wechat|weixin)(?:提醒|通知)我");
    private static final Pattern SMS = Pattern.compile(
            "(?:在|用|通过|走|发到)\\s*(?:短信|sms)(?:上|里|中)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|发短信"
                    + "|(?:短信|sms)(?:提醒|通知)我");
    private static final Pattern EMAIL = Pattern.compile(
            "(?:在|用|通过|走|发到)\\s*(?:邮件|邮箱)(?:上|里|中)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|发邮件"
                    + "|(?:邮件|邮箱)(?:提醒|通知|发我|发一下)"
                    + "|(?:邮件|邮箱)(?:提醒|通知)我");
    private static final Pattern PHONE = Pattern.compile(
            "(?:给我|替我|帮我|由你|你)(?:打|拨)?电话"
                    + "|(?:在|用|通过|走)\\s*电话\\s*(?:提醒|通知|发我|发一下)"
                    + "|电话(?:提醒|通知)我"
                    + "|来电提醒");
    private static final Pattern DINGTALK = Pattern.compile(
            "(?:在|用|通过|走|发到)\\s*(?:钉钉|dingtalk)(?:上|里|中|群)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|(?:钉钉|dingtalk)(?:上|里|中|群)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|发(?:钉钉|dingtalk)");
    private static final Pattern FEISHU = Pattern.compile(
            "(?:在|用|通过|走|发到)\\s*(?:飞书|lark)(?:上|里|中|群)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|(?:飞书|lark)(?:上|里|中|群)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|发(?:飞书|lark)");
    private static final Pattern TELEGRAM = Pattern.compile(
            "(?:在|用|通过|走|发到)\\s*(?:telegram|tg)(?:上|里|中|群)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|(?:telegram|tg)(?:上|里|中|群)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|发(?:telegram|tg)");
    private static final Pattern WHATSAPP = Pattern.compile(
            "(?:在|用|通过|走|发到)\\s*whatsapp(?:上|里|中|群)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|whatsapp(?:上|里|中|群)?\\s*(?:提醒|通知|发我|发一下|发消息)"
                    + "|发whatsapp");

    /** 只拦“让 AI 代做”的外部动作；"提醒我付款"不属于代做，仍可正常记任务。 */
    private static final Pattern EXTERNAL_ACTION = Pattern.compile(
            "(?:帮我|替我|给我|你来|直接|顺便|由你).{0,6}"
                    + "(下单|付款|支付|转账|汇款|订票|订酒店|预订|打车|叫车|点外卖|发消息|回复|操作|签到|抢购|秒杀)");
    private static final Pattern EXTERNAL_BUY = Pattern.compile(
            "(?:帮我|替我|给我)(?:买|购买)");

    private TaskCapabilityGuard() {
    }

    /**
     * @return null 表示当前能力可以承接（至少能记成应用内任务）；
     *         非 null 是给用户看的明确拒绝 + 可执行替代方案。
     */
    public static String unsupportedReason(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String text = message.toLowerCase(Locale.ROOT);
        String channel = detectChannel(text);
        if (channel != null) {
            return "我现在还不能主动在" + channel + "上给你发消息，也做不到定时推送到" + channel + "。"
                    + "目前任务只会记录在应用内；如果要记成应用内任务，去掉“" + channel
                    + "”再说一遍，比如“明天 8 点提醒我打游戏”。";
        }
        String action = detectExternalAction(text);
        if (action != null) {
            return "我不能替你" + action + "，这涉及外部账号、支付或真实世界操作。"
                    + "我可以帮你记录计划或应用内提醒，但不会代你执行。";
        }
        return null;
    }

    /** 供上层判断是否需要给用户提示。 */
    public static boolean isSupported(String message) {
        return unsupportedReason(message) == null;
    }

    private static String detectChannel(String text) {
        // “QQ 音乐”是内容而不是投递通道；只有出现提醒/通知语境才按 QQ 通道处理。
        if (QQ.matcher(text).find() && !isQqMusicOnly(text)) {
            return "QQ";
        }
        if (WECHAT.matcher(text).find()) {
            return "微信";
        }
        if (SMS.matcher(text).find()) {
            return "短信";
        }
        if (EMAIL.matcher(text).find()) {
            return "邮件";
        }
        if (PHONE.matcher(text).find()) {
            return "电话";
        }
        if (DINGTALK.matcher(text).find()) {
            return "钉钉";
        }
        if (FEISHU.matcher(text).find()) {
            return "飞书";
        }
        if (TELEGRAM.matcher(text).find()) {
            return "Telegram";
        }
        if (WHATSAPP.matcher(text).find()) {
            return "WhatsApp";
        }
        return null;
    }

    private static boolean isQqMusicOnly(String text) {
        return text.contains("qq音乐")
                && !Pattern.compile("qq.{0,6}(?:提醒|通知|消息|发我|发一下)").matcher(text).find();
    }

    private static String detectExternalAction(String text) {
        Matcher matcher = EXTERNAL_ACTION.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (EXTERNAL_BUY.matcher(text).find()) {
            return "购买";
        }
        return null;
    }
}
