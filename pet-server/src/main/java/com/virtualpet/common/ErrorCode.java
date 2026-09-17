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

    /** 重复创建宠物。 */
    PET_ALREADY_EXISTS(HttpStatus.CONFLICT, "已经领养过宠物了"),

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
