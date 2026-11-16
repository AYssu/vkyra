package com.vkyra.code.files;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Strict standard charset codecs; malformed input must never be silently saved over a source file. */
public final class TextFileCodec {
    private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    private static final byte[] UTF16_LE_BOM = {(byte) 0xff, (byte) 0xfe};
    private static final byte[] UTF16_BE_BOM = {(byte) 0xfe, (byte) 0xff};

    private TextFileCodec() {}

    public static final class Document {
        private final String text;
        private final Charset charset;
        private final byte[] bom;

        private Document(String text, Charset charset, byte[] bom) {
            this.text = text;
            this.charset = charset;
            this.bom = bom;
        }

        public String getText() { return text; }
        public Charset getCharset() { return charset; }
        public boolean hasBom() { return bom.length != 0; }

        public byte[] encode(String replacement) throws IOException {
            String textToWrite = preserveLineEndings(text, replacement);
            try {
                ByteBuffer bytes = charset.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(textToWrite));
                byte[] result = Arrays.copyOf(bom, bom.length + bytes.remaining());
                bytes.get(result, bom.length, bytes.remaining());
                return result;
            } catch (CharacterCodingException e) {
                throw new IOException("文件包含无法编码的字符，原文件未修改", e);
            }
        }
    }

    public static Document decode(byte[] bytes) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        byte[] bom = new byte[0];
        if (bytes.length >= 4 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe
                && bytes[2] == 0 && bytes[3] == 0) || (bytes[0] == 0 && bytes[1] == 0
                && bytes[2] == (byte) 0xfe && bytes[3] == (byte) 0xff))) {
            throw new IOException("暂不支持 UTF-32，请先转换为 UTF-8 或带 BOM 的 UTF-16");
        }
        if (startsWith(bytes, UTF8_BOM)) {
            bom = UTF8_BOM;
        } else if (startsWith(bytes, UTF16_LE_BOM)) {
            charset = StandardCharsets.UTF_16LE;
            bom = UTF16_LE_BOM;
        } else if (startsWith(bytes, UTF16_BE_BOM)) {
            charset = StandardCharsets.UTF_16BE;
            bom = UTF16_BE_BOM;
        }
        try {
            String text = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, bom.length, bytes.length - bom.length)).toString();
            if (bom.length == 0 && text.indexOf('\0') >= 0) {
                throw new IOException("文件不是 UTF-8 文本；无 BOM 的 UTF-16 或二进制文件需先转换编码");
            }
            return new Document(text, charset, bom);
        } catch (CharacterCodingException e) {
            throw new IOException("无法按 UTF-8/UTF-16 解码文件，原文件未修改", e);
        }
    }

    public static byte[] encodeNew(String text) throws IOException {
        return new Document("", StandardCharsets.UTF_8, new byte[0]).encode(text);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    /** Preserve a uniform existing newline style if a widget normalizes its content to LF. */
    private static String preserveLineEndings(String original, String replacement) {
        if (replacement.indexOf('\r') >= 0) return replacement;
        if (original.indexOf('\r') < 0) return replacement;
        String withoutPairs = original.replace("\r\n", "");
        if (original.contains("\r\n") && withoutPairs.indexOf('\r') < 0
                && withoutPairs.indexOf('\n') < 0) return replacement.replace("\n", "\r\n");
        if (original.indexOf('\n') < 0) return replacement.replace('\n', '\r');
        return replacement;
    }
}
