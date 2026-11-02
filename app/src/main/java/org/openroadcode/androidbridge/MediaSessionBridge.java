package org.openroadcode.androidbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import org.json.JSONObject;
import java.util.List;

/** User-authorized, read-only access to Android media sessions. */
public final class MediaSessionBridge extends NotificationListenerService {
    private static final String SPOTIFY = "com.spotify.music";

    static boolean enabled(Context context) {
        String listeners = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return listeners != null && listeners.contains(
            new ComponentName(context, MediaSessionBridge.class).flattenToString());
    }

    static JSONObject spotifyStatus(Context context) {
        JSONObject result = new JSONObject();
        try {
            result.put("permission_granted", enabled(context));
            result.put("package", SPOTIFY);
            result.put("session_found", false);
            if (!enabled(context)) return result;
            MediaSessionManager manager = (MediaSessionManager)
                context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> sessions = manager.getActiveSessions(
                new ComponentName(context, MediaSessionBridge.class));
            for (MediaController controller : sessions) {
                if (!SPOTIFY.equals(controller.getPackageName())) continue;
                result.put("session_found", true);
                MediaMetadata metadata = controller.getMetadata();
                if (metadata != null) {
                    result.put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
                    result.put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
                    result.put("album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
                    result.put("duration_ms", metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
                }
                PlaybackState playback = controller.getPlaybackState();
                if (playback != null) {
                    result.put("state", playback.getState());
                    result.put("position_ms", playback.getPosition());
                    result.put("actions", playback.getActions());
                    result.put("playing", playback.getState() == PlaybackState.STATE_PLAYING);
                }
                break;
            }
        } catch (SecurityException error) {
            try { result.put("error", "notification_listener_permission_required"); }
            catch (org.json.JSONException ignored) { }
        } catch (Exception error) {
            try { result.put("error", "media_session_unavailable"); }
            catch (org.json.JSONException ignored) { }
        }
        return result;
    }
}
