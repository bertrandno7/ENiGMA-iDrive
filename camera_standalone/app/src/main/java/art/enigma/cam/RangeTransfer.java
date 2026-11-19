package art.enigma.cam;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Standard-Java transfer engine. Never assumes that the dashcam supports ranges. */
final class RangeTransfer {
    interface Opener { HttpURLConnection open(String url) throws Exception; }
    interface Progress { void update(long bytes, long total, String mode); }
    static final class Result {
        final long bytes;
        final String mode;
        final String note;
        Result(long bytes, String mode, String note) {
            this.bytes = bytes; this.mode = mode; this.note = note;
        }
    }
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final long MIN_PARALLEL = 10L * 1024 * 1024;
    private static final long UPDATE_NANOS = 250_000_000L;

    static Result transfer(Opener opener, String url, FileChannel output, Progress progress) throws Exception {
        Probe probe = probe(opener, url);
        String reason = "single stream";
        if (probe.range && probe.total >= MIN_PARALLEL) {
            int connections = probe.total >= 64L * 1024 * 1024 ? 3 : 2;
            try {
                long bytes = multi(opener, url, output, progress, probe.total, connections);
                output.force(false);
                return new Result(bytes, "turbo", connections + " verified HTTP ranges");
            } catch (Exception failure) {
                // A buggy thttpd or overwhelmed Wi-Fi AP must never yield a partial saved clip.
                reason = "parallel not reliable: " + shortReason(failure);
                output.truncate(0);
                output.position(0);
                progress.update(0, probe.total, "normal");
            }
        } else if (!probe.range) reason = "camera does not support verified HTTP ranges";
        long bytes = single(opener, url, output, progress, probe.total);
        output.force(false);
        return new Result(bytes, "normal", reason);
    }

    private static final class Probe {
        final long total;
        final boolean range;
        Probe(long total, boolean range) { this.total = total; this.range = range; }
    }

    private static Probe probe(Opener opener, String url) throws Exception {
        HttpURLConnection c = opener.open(url);
        try {
            c.setReadTimeout(15000);
            c.setRequestProperty("Range", "bytes=0-0");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            rejectErrorDocument(c);
            if (code == 206) {
                long[] values = parseRange(c.getHeaderField("Content-Range"));
                if (values != null && values[0] == 0 && values[1] == 0 && values[2] > 1) {
                    try (InputStream in = c.getInputStream()) {
                        if (in.read() < 0) throw new EOFException("Empty partial response");
                    }
                    return new Probe(values[2], true);
                }
            }
            if (code != 200 && code != 206) throw new IOException("Probe HTTP " + code);
            return new Probe(code == 200 ? c.getContentLengthLong() : -1, false);
        } finally { c.disconnect(); }
    }

    private static long[] parseRange(String header) {
        if (header == null) return null;
        Matcher m = CONTENT_RANGE.matcher(header.trim());
        if (!m.matches()) return null;
        try {
            return new long[]{ Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)) };
        } catch (NumberFormatException ex) { return null; }
    }

    private static void rejectErrorDocument(HttpURLConnection connection) throws IOException {
        String type = connection.getContentType();
        if (type == null) return;
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.contains("text/html") || lower.contains("xml") || lower.contains("json"))
            throw new IOException("Camera returned an error page, not media");
    }

    private static long multi(Opener opener, String url, FileChannel channel, Progress progress,
                              long total, int connections) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(connections);
        AtomicLong received = new AtomicLong();
        AtomicLong lastUpdate = new AtomicLong();
        List<Future<Long>> tasks = new ArrayList<>();
        try {
            progress.update(0, total, "turbo");
            for (int i = 0; i < connections; i++) {
                final long start = (total * i) / connections;
                final long end = (total * (i + 1)) / connections - 1;
                tasks.add(pool.submit(new Callable<Long>() {
                    @Override public Long call() throws Exception {
                        HttpURLConnection c = opener.open(url);
                        try {
                            c.setReadTimeout(15000);
                            c.setRequestProperty("Range", "bytes=" + start + "-" + end);
                            c.setRequestProperty("Accept-Encoding", "identity");
                            int code = c.getResponseCode();
                            if (code != 206) throw new IOException("Range ignored: HTTP " + code);
                            rejectErrorDocument(c);
                            long[] actual = parseRange(c.getHeaderField("Content-Range"));
                            if (actual == null || actual[0] != start || actual[1] != end || actual[2] != total)
                                throw new IOException("Camera returned wrong segment boundaries");
                            long position = start;
                            byte[] buffer = new byte[BUFFER_SIZE];
                            try (InputStream in = c.getInputStream()) {
                                while (position <= end) {
                                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                                    int limit = (int) Math.min(buffer.length, end - position + 1);
                                    int n = in.read(buffer, 0, limit);
                                    if (n < 0) throw new EOFException("Camera dropped a range request");
                                    if (n == 0) continue;
                                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, n);
                                    while (bytes.hasRemaining()) {
                                        int wrote = channel.write(bytes, position);
                                        if (wrote <= 0) throw new IOException("Storage refused data");
                                        position += wrote;
                                    }
                                    long all = received.addAndGet(n);
                                    report(progress, lastUpdate, all, total, "turbo");
                                }
                                // Content-Range and bounded reads constrain this shard exactly.
                            }
                            return position - start;
                        } finally { c.disconnect(); }
                    }
                }));
            }
            long sum = 0;
            for (int i = 0; i < tasks.size(); i++) {
                try { sum += tasks.get(i).get(); }
                catch (ExecutionException ex) {
                    for (Future<?> task : tasks) task.cancel(true);
                    Throwable cause = ex.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new IOException("Range worker crashed", cause);
                }
            }
            if (sum != total || received.get() != total || channel.size() != total)
                throw new IOException("Parallel transfer size mismatch");
            progress.update(total, total, "turbo");
            return total;
        } finally {
            for (Future<?> task : tasks) if (!task.isDone()) task.cancel(true);
            pool.shutdownNow();
            if (!pool.awaitTermination(20, TimeUnit.SECONDS))
                throw new IOException("Camera range workers did not stop safely");
        }
    }

    private static long single(Opener opener, String url, FileChannel channel,
                               Progress progress, long hintedTotal) throws Exception {
        HttpURLConnection c = opener.open(url);
        try {
            c.setReadTimeout(25000);
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Camera file HTTP " + code);
            rejectErrorDocument(c);
            long total = c.getContentLengthLong();
            if (total <= 0) total = hintedTotal;
            long received = 0;
            long lastUpdate = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream in = c.getInputStream()) {
                while (true) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled");
                    int n = in.read(buffer);
                    if (n < 0) break;
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, n);
                    while (bytes.hasRemaining()) {
                        int wrote = channel.write(bytes, received);
                        if (wrote <= 0) throw new IOException("Storage refused data");
                        received += wrote;
                    }
                    long now = System.nanoTime();
                    if (now - lastUpdate >= UPDATE_NANOS) {
                        lastUpdate = now; progress.update(received, total, "normal");
                    }
                }
            }
            if (received <= 0) throw new IOException("Camera returned an empty file");
            if (total > 0 && received != total) throw new EOFException("Camera stopped before completing the clip");
            progress.update(received, total, "normal");
            return received;
        } finally { c.disconnect(); }
    }

    private static void report(Progress progress, AtomicLong last, long done, long total, String mode) {
        long now = System.nanoTime();
        long old = last.get();
        if (now - old > UPDATE_NANOS && last.compareAndSet(old, now)) progress.update(done, total, mode);
    }
    private static String shortReason(Exception ex) {
        String m = ex.getMessage();
        if (m == null) return ex.getClass().getSimpleName();
        return m.substring(0, Math.min(85, m.length()));
    }
}
