package com.course.dbms.common;

/**
 * 统一错误。带错误码，方便客户端/用户定位问题。
 *
 * <p>错误码的唯一来源是 {@link ErrorCode}：一个码只有一种含义，码与含义在类型上绑死。
 * 展示格式为 {@code ✗ [CODE] message}，协议层（{@code Encoder.encodeError}）直接把
 * {@link #toString()} 发给客户端，所以这个格式改动会波及线上传输与文档里的逐字输出。
 */
public class Error extends RuntimeException {

    private final ErrorCode code;

    public Error(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public Error(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 码字面量，如 "SE-0004"。测试断言与线上传输都依赖它保持 String。 */
    public String code() {
        return code.code();
    }

    /** 枚举形态的码，需要查 {@link ErrorCode#meaning()} 时用。 */
    public ErrorCode errorCode() {
        return code;
    }

    /** 一句话含义，便于日志里直接看懂是哪类问题。 */
    public String meaning() {
        return code.meaning();
    }

    /** 拼接最简展示：✗ [CODE] message */
    @Override public String toString() {
        return "✗ [" + code.code() + "] " + getMessage();
    }
}
