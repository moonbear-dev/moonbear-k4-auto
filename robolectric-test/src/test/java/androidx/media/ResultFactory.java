package androidx.media;

import android.support.v4.media.MediaBrowserCompat;

import java.util.List;

/**
 * Test-only bridge into MediaBrowserServiceCompat.Result, whose constructor is
 * package-private. Living in the androidx.media package is the only way a test
 * can drive onLoadChildren() the way the framework/Gearhead client does.
 */
public final class ResultFactory {

    private ResultFactory() {}

    public interface Sink {
        void onResult(List<MediaBrowserCompat.MediaItem> items);
    }

    public static MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>> result(
            MediaBrowserServiceCompat service, final Sink sink) {
        return new MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>>(null) {
            @Override
            public void sendResult(List<MediaBrowserCompat.MediaItem> items) {
                sink.onResult(items);
            }

            @Override
            public void detach() {
                sink.onResult(null);
            }
        };
    }
}
