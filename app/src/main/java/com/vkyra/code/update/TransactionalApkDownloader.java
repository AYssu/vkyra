package com.vkyra.code.update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** No destination is published until transport, length and APK verification all succeed. */
final class TransactionalApkDownloader {
    static final long MAX_APK_BYTES = 1024L * 1024 * 1024;
    interface Validator { void validate(File apk) throws Exception; }
    interface Progress { void update(int percent, long downloaded, long total); }

    static void download(Call call, File destination, BooleanSupplier cancelled,
                         Validator validator, Progress progress) throws Exception {
        Files.createDirectories(destination.toPath().getParent());
        File staging = Files.createTempFile(destination.toPath().getParent(), ".vkyra-update-", ".part").toFile();
        try {
            checkCancelled(cancelled);
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
                ResponseBody body = response.body();
                if (body == null) throw new IOException("下载内容为空");
                long total = body.contentLength();
                if (total > MAX_APK_BYTES) throw new IOException("安装包过大");
                long count = 0;
                int lastProgress = -1;
                try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(staging)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = input.read(buffer)) != -1;) {
                        checkCancelled(cancelled);
                        count += read;
                        if (count > MAX_APK_BYTES) throw new IOException("安装包过大");
                        output.write(buffer, 0, read);
                        int percent = total > 0 ? (int) Math.min(99, count * 100 / total) : 0;
                        if (percent != lastProgress) { progress.update(percent, count, total); lastProgress = percent; }
                    }
                    if (count == 0 || (total >= 0 && count != total)) throw new IOException("安装包下载不完整");
                    output.flush();
                    output.getFD().sync();
                }
                checkCancelled(cancelled);
                validator.validate(staging);
                checkCancelled(cancelled);
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                progress.update(100, count, total);
            }
        } finally { Files.deleteIfExists(staging.toPath()); }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
    }
}
