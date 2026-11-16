package com.vkyra.code.files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.Assert.*;

public class AtomicTextFileStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void unchangedUtf8AndMixedNewlinesRoundTripByteForByte() throws Exception {
        File file = temporary.newFile("mixed.cpp");
        byte[] original = "// 中文\r\nint main() {\n}\rreturn 0;".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), original);
        String text = AtomicTextFileStore.read(file).getText();
        AtomicTextFileStore.write(file, text);
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    }

    @Test public void preservesBomAndUtf16EncodingOnEdit() throws Exception {
        for (String encoding : new String[]{"UTF-8", "UTF-16LE", "UTF-16BE"}) {
            byte[] bom = encoding.equals("UTF-8") ? new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf}
                    : encoding.equals("UTF-16LE") ? new byte[]{(byte) 0xff, (byte) 0xfe}
                    : new byte[]{(byte) 0xfe, (byte) 0xff};
            File file = temporary.newFile(encoding + ".cpp");
            Files.write(file.toPath(), withPrefix(bom, "初稿\r\n".getBytes(encoding)));
            AtomicTextFileStore.write(file, "编辑\n");
            assertArrayEquals(withPrefix(bom, "编辑\r\n".getBytes(encoding)), Files.readAllBytes(file.toPath()));
        }
    }

    @Test public void failedEncodingLeavesExistingFileUntouched() throws Exception {
        File file = temporary.newFile("safe.cpp");
        byte[] original = "int main() {}".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), original);
        try {
            AtomicTextFileStore.write(file, "unpaired surrogate \ud800");
            fail("Invalid Unicode must not be silently replaced");
        } catch (IOException expected) {
            assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        }
        assertEquals(1, temporary.getRoot().list().length);
    }

    @Test public void undecodableOriginalIsNeverOverwritten() throws Exception {
        File file = temporary.newFile("legacy.cpp");
        byte[] original = {(byte) 0xff, 0x31, (byte) 0xc3};
        Files.write(file.toPath(), original);
        try {
            AtomicTextFileStore.write(file, "replacement");
            fail("Unknown encoding must require a deliberate conversion");
        } catch (IOException expected) {
            assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        }
    }

    @Test public void followsSymlinkAndPreservesExecutablePermission() throws Exception {
        File target = temporary.newFile("build.sh");
        Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rwxr-x---"));
        File link = new File(temporary.getRoot(), "link.sh");
        Files.createSymbolicLink(link.toPath(), target.toPath());
        AtomicTextFileStore.write(link, "new");
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertEquals("new", AtomicTextFileStore.read(target).getText());
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())));
        assertEquals(2, temporary.getRoot().list().length);
    }

    @Test public void newFileUsesUtf8AndDoesNotInventFinalNewline() throws Exception {
        File file = new File(temporary.getRoot(), "new.cpp");
        AtomicTextFileStore.write(file, "// 中文");
        assertArrayEquals("// 中文".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file.toPath()));
    }

    @Test public void unsupportedUtf32DoesNotMasqueradeAsUtf16() throws Exception {
        try {
            TextFileCodec.decode(new byte[]{(byte) 0xff, (byte) 0xfe, 0, 0, 65, 0, 0, 0});
            fail("Unsupported UTF-32 must be reported");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("UTF-32"));
        }
    }

    private static byte[] withPrefix(byte[] prefix, byte[] bytes) {
        byte[] result = java.util.Arrays.copyOf(prefix, prefix.length + bytes.length);
        System.arraycopy(bytes, 0, result, prefix.length, bytes.length);
        return result;
    }
}
