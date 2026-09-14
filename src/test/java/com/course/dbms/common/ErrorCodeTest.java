package com.course.dbms.common;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * 守住 "一个错误码只有一种含义" 这条性质，以及文档与代码不漂移。
 *
 * 这些检查以前只能靠人肉 review：错误码是散在各处的裸字符串，同一个码被反复复用
 * （SE-0004 曾同时表示六种含义），文档「附录 A」也只写到 SE-0008 就停更了。
 * 现在码集中在 {@link ErrorCode}，这几条不变量就可以自动验证：
 *
 *   1. 码唯一、格式统一；
 *   2. 每个码都真的在用（没有死码）；
 *   3. 源码里不再出现裸的码字符串（防止有人绕过枚举又写出重码）；
 *   4. 手册「附录 A」与枚举一一对应（文档是交付物，不许漂移）。
 */
public class ErrorCodeTest {

    private static final Pattern CODE_IN_BACKTICKS = Pattern.compile("`([A-Z]{2}-\\d{4})`");
    /** 绕过枚举直接写码的构造，例如 new Error("SE-0004", ...)。 */
    private static final Pattern BARE_CODE_CTOR =
            Pattern.compile("Error\\(\\s*\"[A-Z]{2}-\\d{4}\"");
    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Z]{2}-\\d{4}$");

    // ---- 1. 码本身 ----

    @Test public void codesAreUniqueAndWellFormed() {
        Set<String> seen = new HashSet<>();
        List<String> dup = new ArrayList<>();
        for (ErrorCode ec : ErrorCode.all()) {
            assertTrue("码格式应为 前缀-4位数字，实际是 " + ec.code(),
                    WELL_FORMED.matcher(ec.code()).matches());
            if (!seen.add(ec.code())) dup.add(ec.code());
            assertNotNull(ec.code() + " 缺少含义说明", ec.meaning());
            assertFalse(ec.code() + " 的含义是空的", ec.meaning().isEmpty());
        }
        assertTrue("错误码必须唯一，重复的有: " + dup, dup.isEmpty());
    }

    @Test public void lookupByCodeRoundTrips() {
        for (ErrorCode ec : ErrorCode.all()) {
            assertSame(ec.code() + " 反查不到自己", ec, ErrorCode.of(ec.code()));
        }
        assertNull("未知码应返回 null", ErrorCode.of("ZZ-9999"));
    }

    // ---- 2. 没有死码：每个码都在源码里被引用过 ----

    @Test public void everyCodeIsReferencedInSources() throws IOException {
        String sources = concatJavaSources(new File("src/main/java"),
                new File("src/main/java/com/course/dbms/common/ErrorCode.java").getAbsoluteFile());
        List<String> unused = new ArrayList<>();
        for (ErrorCode ec : ErrorCode.all()) {
            if (!sources.contains(ec.name())) unused.add(ec.name() + "(" + ec.code() + ")");
        }
        assertTrue("这些码定义了却没人用（死码）: " + unused, unused.isEmpty());
    }

    // ---- 3. 源码里不许再有裸的码字符串 ----

    @Test public void noBareCodeLiteralOutsideTheEnum() throws IOException {
        List<String> hits = new ArrayList<>();
        collectBareCodes(new File("src/main/java"), hits,
                new File("src/main/java/com/course/dbms/common/ErrorCode.java").getAbsoluteFile());
        assertTrue("错误码必须走 ErrorCode 枚举，以下位置又直接写了码字符串（会重新出现「一码多义」）:\n"
                + String.join("\n", hits), hits.isEmpty());
    }

    // ---- 4. 手册「附录 A」与枚举对齐 ----

    @Test public void manualAppendixMatchesEnum() throws IOException {
        File manual = new File("项目理解手册.md");
        assertTrue("交付物「项目理解手册.md」缺失，无法核对附录 A", manual.isFile());
        String text = new String(Files.readAllBytes(manual.toPath()), StandardCharsets.UTF_8);

        int from = text.indexOf("## 附录 A");
        int to = text.indexOf("## 附录 B");
        assertTrue("手册里找不到「## 附录 A：错误码总表」", from >= 0);
        assertTrue("手册里找不到「## 附录 B」（附录 A 的结束位置）", to > from);

        Set<String> inDoc = new LinkedHashSet<>();
        Matcher m = CODE_IN_BACKTICKS.matcher(text.substring(from, to));
        while (m.find()) inDoc.add(m.group(1));

        Set<String> codes = new TreeSet<>();
        for (ErrorCode ec : ErrorCode.all()) codes.add(ec.code());

        Set<String> missing = new TreeSet<>(codes);
        missing.removeAll(inDoc);
        assertTrue("这些错误码在代码里存在，却没进「附录 A：错误码总表」: " + missing, missing.isEmpty());

        Set<String> extra = new TreeSet<>(inDoc);
        extra.removeAll(codes);
        extra.removeAll(ErrorCode.retiredCodes());
        assertTrue("「附录 A」里有代码中不存在的错误码（码改过而文档没跟上？）: " + extra, extra.isEmpty());
    }

    // ---- 工具 ----

    private static String concatJavaSources(File dir, File skip) throws IOException {
        StringBuilder sb = new StringBuilder();
        appendJavaSources(dir, sb, skip);
        return sb.toString();
    }

    private static void appendJavaSources(File dir, StringBuilder sb, File skip) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.equals(skip)) continue;
            if (f.isDirectory()) {
                appendJavaSources(f, sb, skip);
            } else if (f.getName().endsWith(".java")) {
                sb.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).append('\n');
            }
        }
    }

    private static void collectBareCodes(File dir, List<String> hits, File skip) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.equals(skip)) continue;
            if (f.isDirectory()) {
                collectBareCodes(f, hits, skip);
                continue;
            }
            if (!f.getName().endsWith(".java")) continue;
            String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (BARE_CODE_CTOR.matcher(lines[i]).find()) {
                    hits.add(f.getPath() + ":" + (i + 1) + "  " + lines[i].trim());
                }
            }
        }
    }
}
