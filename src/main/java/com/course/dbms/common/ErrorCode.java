package com.course.dbms.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 错误码总表。**每个错误码只有一种含义**——这是本枚举存在的唯一理由。
 *
 * <p>此前错误码是散在各处的裸字符串，于是同一个码被反复复用：{@code SE-0004} 一个码
 * 同时表示"列不存在 / 列名有歧义 / 表别名重复 / 未知聚合函数 / ORDER BY 列不存在 /
 * 不支持的字段类型"六种含义，用户拿着错误码根本查不到问题。现在码与含义在类型上绑死，
 * 一处一个含义，{@link ErrorCodeTest} 会守住这条性质。
 *
 * <p>命名规则：{@code 前缀-4位数字}。前缀代表模块：
 * <ul>
 *   <li>ST 存储与字段编码</li>
 *   <li>CS 缓存层</li>
 *   <li>TB 表</li>
 *   <li>CT 目录</li>
 *   <li>LX 词法</li>
 *   <li>SY 语法</li>
 *   <li>SE 语义</li>
 *   <li>PL 计划</li>
 *   <li>EX 执行</li>
 *   <li>TX 事务</li>
 *   <li>CL 客户端</li>
 *   <li>SV 服务端</li>
 * </ul>
 *
 * <p>已废弃、不再分配的号：{@code SE-0013}（含义并入 {@link #SE_COLUMN_NOT_FOUND}）、
 * {@code PL-0001}（并入 {@link #SE_UNKNOWN_STATEMENT}）、
 * {@code EX-0002}（并入 {@link #SE_UNKNOWN_AGGREGATE}）、{@code CT-0001}（从未使用）。
 * 文档「附录 A：错误码总表」与之一一对应，由 {@link ErrorCodeTest} 核对。
 */
public enum ErrorCode {

    // ---------- ST 存储与字段编码 ----------
    ST_CREATE_DB_DIR("ST-0001", "创建数据库目录失败"),
    ST_CREATE_TABLE_FILE("ST-0002", "创建表文件失败"),
    ST_PAGE_SIZE_MISMATCH_WRITE("ST-0003", "写入页时页大小不匹配"),
    ST_TRUNCATE_BELOW_ZERO("ST-0004", "截断位置不能小于零"),
    ST_CREATE_WAL_DIR("ST-0005", "创建 WAL 目录失败"),
    ST_FIELD_ENCODE_UNKNOWN("ST-0006", "未知字段类型的编码"),
    ST_FIELD_DECODE_UNKNOWN("ST-0007", "未知字段类型的解码"),
    ST_FIELD_PARSE_LITERAL_UNKNOWN("ST-0008", "未知字段类型的字面量解析"),
    ST_PAGE_SIZE_MISMATCH_READ("ST-0009", "读取页时页大小不匹配"),
    ST_OPEN_FILE("ST-0010", "打开表文件失败"),
    ST_READ_LENGTH("ST-0011", "读取文件长度失败"),
    ST_READ_PAGE("ST-0012", "读取页失败"),
    ST_WRITE_PAGE("ST-0013", "写入页失败"),
    ST_TRUNCATE("ST-0014", "截断文件失败"),
    ST_WAL_APPEND("ST-0015", "WAL 追加失败"),
    ST_WAL_PARSE("ST-0016", "WAL 解析失败"),
    ST_WAL_READ("ST-0017", "读取 WAL 失败"),
    ST_WAL_TRUNCATE("ST-0018", "截断 WAL 失败"),

    // ---------- CS 缓存层 ----------
    CS_CACHE_CAPACITY("CS-0001", "缓存容量非法"),

    // ---------- TB 表 ----------
    TB_TABLE_NOT_FOUND("TB-0001", "表不存在"),

    // ---------- CT 目录 ----------
    CT_TABLE_EXISTS("CT-0002", "表已存在"),
    CT_UNKNOWN_CONSTRAINT_KIND("CT-0003", "未知的约束类型"),

    // ---------- LX 词法 / SY 语法 ----------
    LX_LEX("LX-0001", "词法错误"),
    SY_SYNTAX("SY-0001", "语法错误"),

    // ---------- SE 语义 ----------
    SE_UNKNOWN_STATEMENT("SE-0000", "未知的语句类型"),
    SE_TABLE_NEEDS_COLUMN("SE-0001", "表至少需要一列"),
    SE_DUPLICATE_COLUMN("SE-0002", "列名重复"),
    SE_INSERT_VALUE_COUNT("SE-0003", "插入值个数与列数不一致"),
    SE_COLUMN_NOT_FOUND("SE-0004", "列不存在"),
    SE_INSERT_TYPE_MISMATCH("SE-0005", "插入值类型与列类型不匹配"),
    SE_GROUP_BY_MISSING("SE-0006", "非聚合列未出现在 GROUP BY"),
    SE_INDEX_EXISTS("SE-0007", "索引名已存在"),
    SE_DESERIALIZE_ROW("SE-0008", "行数据反序列化失败"),
    SE_RECORD_TOO_LARGE("SE-0009", "记录超过单页容量"),
    SE_NOT_NULL_VIOLATION("SE-0010", "违反 NOT NULL 约束"),
    SE_UNIQUE_VIOLATION("SE-0011", "违反唯一性约束"),
    SE_CHECK_VIOLATION("SE-0012", "违反 CHECK 约束"),
    SE_INSERT_COLUMN_DUPLICATE("SE-0014", "INSERT 列清单里有重复列"),
    SE_CONSTRAINT_NAME_DUPLICATE("SE-0015", "约束名重复"),
    SE_MULTIPLE_PRIMARY_KEY("SE-0016", "一张表只能有一个 PRIMARY KEY"),
    SE_AMBIGUOUS_COLUMN("SE-0017", "列名有歧义"),
    SE_DUPLICATE_TABLE_ALIAS("SE-0018", "表名或别名重复"),
    SE_UNKNOWN_AGGREGATE("SE-0019", "未知的聚合函数"),
    SE_ORDER_BY_COLUMN_NOT_FOUND("SE-0020", "ORDER BY 列不存在"),
    SE_UNSUPPORTED_TYPE("SE-0021", "不支持的字段类型"),
    SE_SERIALIZE_ROW("SE-0022", "行数据序列化失败"),
    SE_UNKNOWN_TYPE_NAME("SE-0023", "未知的字段类型名"),
    SE_UNKNOWN_FIELD_TAG("SE-0024", "未知的字段类型标签"),
    SE_ROW_WIDTH_MISMATCH("SE-0025", "行宽与列数不一致（内部不变量）"),
    SE_DEFAULT_TYPE_MISMATCH("SE-0026", "DEFAULT 值与列类型不匹配"),
    SE_DEFAULT_NULL_ON_NOT_NULL("SE-0027", "NOT NULL 列的默认值不能为 NULL"),
    SE_AGGREGATE_ARG_TYPE("SE-0028", "SUM/AVG 的参数必须为数值类型"),
    SE_INSERT_COLUMN_COUNT("SE-0029", "插入值个数与列清单个数不一致"),
    SE_ORDER_BY_NOT_OUTPUT("SE-0030", "分组查询的排序键必须是输出列"),

    // ---------- PL 计划 ----------
    PL_TXN_NOT_IN_PLAN("PL-0002", "事务控制语句不该进入计划"),

    // ---------- EX 执行 ----------
    EX_UNKNOWN_OPERATOR("EX-0001", "未知的比较运算符"),

    // ---------- TX 事务 ----------
    TX_NO_ACTIVE_TXN("TX-0001", "没有活动事务"),
    TX_ALREADY_IN_TXN("TX-0002", "已在事务中"),
    TX_LOCK_TIMEOUT("TX-0003", "等待锁超时"),
    TX_FREE_PAGE_IN_TXN("TX-0004", "事务内不能释放页"),
    TX_UNKNOWN_OP("TX-0005", "未知的事务操作"),
    TX_LOCK_INTERRUPTED("TX-0006", "等待锁时被中断"),

    // ---------- CL 客户端 ----------
    CL_BAD_ARGUMENT("CL-0001", "命令行参数错误"),
    CL_CONNECT_FAILED("CL-0002", "无法连接服务端"),
    CL_SCRIPT_READ_FAILED("CL-0003", "读取 SQL 脚本文件失败"),
    CL_CONNECTION_LOST("CL-0004", "与服务端的连接中断"),
    CL_INTERNAL("CL-0005", "客户端内部错误"),

    // ---------- SV 服务端 ----------
    SV_INTERNAL("SV-0001", "服务端内部错误"),
    SV_BAD_ARGUMENT("SV-0002", "服务端启动参数错误");

    private final String code;
    private final String meaning;

    ErrorCode(String code, String meaning) {
        this.code = code;
        this.meaning = meaning;
    }

    /** 形如 "SE-0004"。线上传输与用户看到的就是这个字面量。 */
    public String code() {
        return code;
    }

    /** 一句话含义，用于错误码总表与排查提示。 */
    public String meaning() {
        return meaning;
    }

    /** 按码字面量反查；未知码返回 null。测试与文档核对用。 */
    public static ErrorCode of(String code) {
        for (ErrorCode ec : values()) {
            if (ec.code.equals(code)) return ec;
        }
        return null;
    }

    /** 全部错误码，按声明顺序。 */
    public static List<ErrorCode> all() {
        List<ErrorCode> out = new ArrayList<>(values().length);
        for (ErrorCode ec : values()) out.add(ec);
        return out;
    }

    /**
     * 已废弃、不再分配的码字面量。它们不再是枚举项，但文档「附录 A」要保留这些行，
     * 说明"这个号并入了谁"——否则以前见过旧表的人会以为那个码凭空消失了。
     */
    public static List<String> retiredCodes() {
        List<String> out = new ArrayList<>(4);
        out.add("SE-0013");   // 含义并入 SE-0004 列不存在
        out.add("PL-0001");   // 含义并入 SE-0000 未知的语句类型
        out.add("EX-0002");   // 含义并入 SE-0019 未知的聚合函数
        out.add("CT-0001");   // 从未被构造过：原先由 Error.ct() 写死，而 ct() 无人调用
        return out;
    }

    @Override public String toString() {
        return code;
    }
}
