package com.course.dbms.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 分帧器：把 {Package} 按长度前缀编成一帧，避免粘包。
 * 帧格式： [4-byte payload_len][1-byte flag][payload 字节]
 *   flag 0=成功结果，1=错误，2=trace 中间输出（其后仍跟随最终结果/错误包）。
 */
public final class Packager {

    private Packager() {}

    public static void write(DataOutputStream out, Package pkg) throws IOException {
        out.writeInt(pkg.payload.length);
        out.writeByte(pkg.error ? 1 : (pkg.trace ? 2 : 0));
        out.write(pkg.payload);
        out.flush();
    }

    /** 读一帧；连接被对端关闭时抛 EOFException。 */
    public static Package read(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte flag = in.readByte();
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Package(flag == 1, flag == 2, payload);
    }
}
