package art.enigma.cam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Network;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.function.Consumer;

/** MediaStore-safe verified download; parallel mode has automatic serial fallback. */
final class FastDownload {
    interface Progress { void update(long done, long total, String mode); }

    static Uri download(Network network, ContentResolver resolver, String host, String path,
                        Progress progress, Consumer<String> transferNote) throws Exception {
        String normalized = CameraProtocol.normalizeCameraPath(path);
        if (normalized.isEmpty()) throw new SecurityException("Invalid file path");
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") ||
              lower.endsWith(".ts") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")))
            throw new SecurityException("Unsupported media file type");
        if (network == null) throw new java.io.IOException("Dashcam Wi-Fi disconnected");
        String address = CameraProtocol.urlForFile(host, normalized);
        String mime = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg" :
                      lower.endsWith(".avi") ? "video/x-msvideo" :
                      lower.endsWith(".ts") ? "video/mp2t" : "video/mp4";
        boolean picture = mime.startsWith("image/");
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (picture ? "Pictures/" : "Movies/") + "ENiGMA CAM");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = picture ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) :
                                   MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri dest = resolver.insert(collection, cv);
        if (dest == null) throw new java.io.IOException("Unable to create a phone media file");
        try {
            try (ParcelFileDescriptor fd = resolver.openFileDescriptor(dest, "rw")) {
                if (fd == null) throw new java.io.IOException("Unable to open phone media file");
                try (FileOutputStream stream = new FileOutputStream(fd.getFileDescriptor());
                     FileChannel channel = stream.getChannel()) {
                    RangeTransfer.Result result = RangeTransfer.transfer(
                         target -> CameraProtocol.open(network, target), address, channel,
                         (done, total, mode) -> progress.update(done, total, mode));
                    if (result.bytes < 1) throw new java.io.IOException("Empty recording");
                    transferNote.accept(result.note);
                }
            }
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(dest, cv, null, null);
            return dest;
        } catch (Exception error) {
            try { resolver.delete(dest, null, null); } catch (Exception ignored) { }
            throw error;
        }
    }
}
