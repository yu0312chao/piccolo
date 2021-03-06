/*
 * Copyright 2019 ukuz90
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ukuz.piccolo.api.exchange.support;

import io.github.ukuz.piccolo.api.connection.Cipher;
import io.github.ukuz.piccolo.api.connection.Connection;
import io.github.ukuz.piccolo.api.exchange.protocol.Packet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author ukuz90
 */
public abstract class ByteBufMessage implements BaseMessage {

    private static final Logger LOGGER = LoggerFactory.getLogger(ByteBufMessage.class);
    private byte commandType;
    private byte compressType;
    private boolean encrypt = true;
    private int sessionId;
    private Connection connection;
    private static final AtomicInteger ID_SEQ = new AtomicInteger();

    public ByteBufMessage(Connection connection, byte commandType) {
        this.connection = connection;
        this.commandType = commandType;
    }

    @Override
    public void decodeBody(Packet packet) {
        byte[] payload = packet.getPayload();
        commandType = packet.getCommandType();
        decomposeFlag(packet.getFlag());
        sessionId = packet.getSessionId();
        if (encrypt) {
            // 解密
            byte[] tmp = null;
            if (getCipher() != null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("decodeBody use encrypt, cipher: {}, packet: {} ", getCipher(), packet);
                }
                tmp = getCipher().decrypt(payload);
            }
            if (tmp != null && tmp.length > 0) {
                payload = tmp;
            } else {
                encrypt = false;
            }
        }
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        decodeBody0(buf);
    }


    @Override
    public Packet encodeBody() {
//        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
        ByteBuf buf = null;
        try {
            buf = connection.getChannel().alloc().buffer();
            encodeBody0(buf);
            byte[] payload = new byte[buf.readableBytes()];
            buf.readBytes(payload);
            if (encrypt) {
                // 加密
                byte[] tmp = null;
                if (getCipher() != null) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("decodeBody use encrypt, cipher: {} cmd:{}", getCipher(), commandType);
                    }
                    tmp = getCipher().encrypt(payload);
                }
                if (tmp != null && tmp.length > 0) {
                    payload = tmp;
                } else {
                    encrypt = false;
                }
            }

            Packet packet = new Packet();
            packet.setCmd(commandType);
            packet.setMagic((short) 0xbcc0);
            packet.setFlag(assemblyFlag());
            packet.setSessionId(sessionId);
            packet.setPayload(payload);
            packet.setLength(payload.length);
            return packet;
        } finally {
            if (buf != null) {
                buf.release();
            }
        }

    }

    public void writeByte(ByteBuf buf, byte b) {
        buf.writeByte(b);
    }

    public void writeShort(ByteBuf buf, short s) {
        buf.writeShort(s);
    }

    public void writeInt(ByteBuf buf, int i) {
        buf.writeInt(i);
    }

    public void writeLong(ByteBuf buf, long l) {
        buf.writeLong(l);
    }

    public void writeLongs(ByteBuf buf, long[] l) {
        if (l == null || l.length == 0) {
            buf.writeShort(0);
            return;
        }
        if (l.length < Short.MAX_VALUE) {
            buf.writeShort(l.length);
        } else {
            buf.writeShort(Short.MAX_VALUE).writeInt(l.length - Short.MAX_VALUE);
        }
        for (int i = 0; i < l.length; i++) {
            buf.writeLong(l[i]);
        }
    }

    public void writeFloat(ByteBuf buf, float f) {
        buf.writeInt(Float.floatToIntBits(f));
    }

    public void writeDouble(ByteBuf buf, double d) {
        buf.writeLong(Double.doubleToLongBits(d));
    }

    public void writeBoolean(ByteBuf buf, boolean b) {
        buf.writeBoolean(b);
    }

    public void writeString(ByteBuf buf, String content) {
        writeBytes(buf, content == null ? null : content.getBytes(StandardCharsets.UTF_8));
    }

    public void writeBytes(ByteBuf buf, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            buf.writeShort(0);
        } else if (bytes.length < Short.MAX_VALUE) {
            buf.writeShort(bytes.length).writeBytes(bytes);
        } else {
            buf.writeShort(Short.MAX_VALUE).writeInt(bytes.length - Short.MAX_VALUE).writeBytes(bytes);
        }
    }

    public byte readByte(ByteBuf buf) {
        return buf.readByte();
    }

    public short readShort(ByteBuf buf) {
        return buf.readShort();
    }

    public int readInt(ByteBuf buf) {
        return buf.readInt();
    }

    public long readLong(ByteBuf buf) {
        return buf.readLong();
    }

    public long[] readLongs(ByteBuf buf) {
        int len = buf.readShort();
        if (len > Short.MAX_VALUE) {
            len += buf.readInt();
        }
        long[] ret = new long[len];
        for (int i = 0; i < len; i++) {
            ret[i] = buf.readLong();
        }
        return ret;
    }

    public float readFloat(ByteBuf buf) {
        return Float.intBitsToFloat(buf.readInt());
    }

    public double readDouble(ByteBuf buf) {
        return Double.longBitsToDouble(buf.readLong());
    }

    public boolean readBoolean(ByteBuf buf) {
        return buf.readBoolean();
    }

    public String readString(ByteBuf buf) {
        return new String(readBytes(buf), StandardCharsets.UTF_8);
    }

    public byte[] readBytes(ByteBuf buf) {
        int len = buf.readShort();
        if (len > Short.MAX_VALUE) {
            len += buf.readInt();
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return bytes;
    }

    protected abstract void decodeBody0(ByteBuf buf);

    protected abstract void encodeBody0(ByteBuf buf);

    private void decomposeFlag(byte flag) {
        compressType = (byte) (flag & 0x7);
        encrypt = ((flag >> 3 & 1 ) == 1);
    }

    private byte assemblyFlag() {
        if (encrypt) {
            return (byte) (0x08 | (0x07 & compressType));
        } else {
            return (byte) (0x07 & compressType);
        }
    }

    public byte getCommandType() {
        return commandType;
    }

    public void setCommandType(byte commandType) {
        this.commandType = commandType;
    }

    public byte getCompressType() {
        return compressType;
    }

    public void setCompressType(byte compressType) {
        this.compressType = compressType;
    }

    public int getSessionId() {
        return sessionId;
    }

    public void setSessionId(int sessionId) {
        this.sessionId = sessionId;
    }

    public Cipher getCipher() {
        return connection.getSessionContext().getCipher();
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void setRaw(boolean isRaw) {
        if (isRaw) {
            encrypt = false;
            compressType = 0;
        }
    }
}
