package com.virtualpet.common;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码（TECH_DESIGN 5.4）。
 *
 * <p>{@code code} 会原样出现在响应体里，前端按它判断；{@code message} 是给用户看的
 * 中文默认文案，调用方可以覆盖成更具体的说法（例如「它现在不饿」）。</p>
 */
public enum ErrorCode {

    /** 成功。 */
    OK(HttpStatus.OK, "success"),

    /** 操作枚举非法。 */
    INVALID_ACTION(HttpStatus.BAD_REQUEST, "这个操作它做不了"),

    /** 宠物名字非法。 */
    INVALID_NAME(HttpStatus.BAD_REQUEST, "名字需要 1–8 个字符"),

    /** 请求体、参数或枚举值不合法。 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不合法"),

    /** 令牌缺失或无效。 */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "登录状态已失效，请刷新页面"),

    /** 尚未创建宠物。 */
    PET_NOT_FOUND(HttpStatus.NOT_FOUND, "还没有领养宠物"),

    /** 接口不存在。 */
    NOT_FOUND(HttpStatus.NOT_FOUND, "接口不存在"),

    /**
     * 宠物槽位已满，领养不下（PRD 2.2，多宠物槽）。
     *
     * <p>取代了原先的 {@code PET_ALREADY_EXISTS}（「已经领养过宠物了」）。
     * 单宠物槽时代「重复领养」和「槽位已满」是同一件事，多宠物槽之后只剩后者：
     * 三个槽位都占用时才会走到这里。旧错误码没有保留 —— 留着它就是一个
     * 永远不会返回的码，文档和排查都会被它误导。</p>
     */
    PET_SLOTS_FULL(HttpStatus.CONFLICT, "宠物已经满了，先送走一只再领养"),

    /** 操作冷却中。 */
    ACTION_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "它还在缓一缓，稍等一下"),

    /** 当前操作无效果。 */
    ACTION_NO_EFFECT(HttpStatus.CONFLICT, "现在做这个没有效果"),

    /** 并发冲突且重试用尽。 */
    CONFLICT(HttpStatus.CONFLICT, "操作太频繁，请重试"),

    /** 好友码不存在（P1 对战）。不区分"格式对但没人"和"根本没这个码"，避免被枚举。 */
    FRIEND_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "没有找到这个好友码"),

    /** 拿自己的好友码挑战自己。 */
    SELF_CHALLENGE(HttpStatus.CONFLICT, "不能挑战自己"),

    /** 对方还没有宠物，没得打。 */
    OPPONENT_NO_PET(HttpStatus.CONFLICT, "对方还没有领养宠物"),

    /** 对战记录不存在，或者与当前玩家无关。 */
    BATTLE_NOT_FOUND(HttpStatus.NOT_FOUND, "没有这场对战的记录"),

    /**
     * 请求过于频繁，被限流拦下（PRD 6.2）。
     *
     * <p>和 {@link #ACTION_COOLDOWN} 不同：那个是<b>游戏规则</b>（宠物还没缓过来，
     * 换个人来点也一样），这个是<b>基础设施保护</b>（同一个来源发得太密），
     * 换一个网络出口就不受限。两者都是 429，但前端该给的提示不一样。</p>
     */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求太频繁了，缓一缓再试"),

    /** 未预期的服务端错误。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器开小差了，请稍后再试");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
