package g2s.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 源文件解码。openGauss 脚本常见 UTF-8 与 GB18030（中文注释），
 * 两者用内置 charset 覆盖，不引入第三方依赖。
 */
public final class Encoding {

    public final String text;
    public final Charset charset;
    public final String charsetName;
    public final boolean hadBom;
    public final int byteLength;

    private Encoding(String text, Charset charset, String charsetName, boolean hadBom, int byteLength) {
        this.text = text;
        this.charset = charset;
        this.charsetName = charsetName;
        this.hadBom = hadBom;
        this.byteLength = byteLength;
    }

    private static String strictDecode(byte[] data, int offset, int length, Charset cs)
            throws CharacterCodingException {
        CharsetDecoder decoder = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(data, offset, length)).toString();
    }

    /** BOM -> UTF-8 -> GB18030 -> Latin-1 依次尝试，逐个用严格解码验证。 */
    public static Encoding read(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);

        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            return new Encoding(new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8, "UTF-8", true, raw.length);
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            return new Encoding(new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE),
                    StandardCharsets.UTF_16LE, "UTF-16LE", true, raw.length);
        }

        try {
            return new Encoding(strictDecode(raw, 0, raw.length, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8, "UTF-8", false, raw.length);
        } catch (CharacterCodingException ignored) {
            // 继续尝试 GB18030
        }

        Charset gb18030 = Charset.forName("GB18030");
        try {
            return new Encoding(strictDecode(raw, 0, raw.length, gb18030),
                    gb18030, "GB18030", false, raw.length);
        } catch (CharacterCodingException ignored) {
            // 继续尝试兜底编码
        }

        return new Encoding(new String(raw, StandardCharsets.ISO_8859_1),
                StandardCharsets.ISO_8859_1, "ISO-8859-1", false, raw.length);
    }
}
