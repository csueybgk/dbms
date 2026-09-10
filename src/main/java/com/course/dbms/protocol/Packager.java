package com.course.dbms.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 分帧器：把 {Package} 按长度前缀编成一帧，避免粘包。
 * 帧格式： [4-byte payload_len][1-byte err_flag][payload 字节]
 *   err_flag 0=成功，1=错误。
 */
public final class Packager {

    private Packager() {}

    public static void write(DataOutputStream out, Package pkg) throws IOException {
        out.writeInt(pkg.payload.length);
        out.writeByte(pkg.error ? 1 : 0);
        out.write(pkg.payload);
        out.flush();
    }

    /** 读一帧；连接被对端关闭时抛 EOFException。 */
    public static Package read(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte err = in.readByte();
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Package(err == 1, payload);
    }
}
