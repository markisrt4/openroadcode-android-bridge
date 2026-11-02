package org.openroadcode.androidbridge;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import org.json.JSONObject;
import java.util.List;

/** User-authorized access to Android media sessions. */
public final class MediaSessionBridge extends NotificationListenerService {
    private static final String SPOTIFY = "com.spotify.music";

    static boolean enabled(Context context) {
        String listeners = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return listeners != null && listeners.contains(
            new ComponentName(context, MediaSessionBridge.class).flattenToString());
    }


    static JSONObject spotifyCommand(Context context, String command) {
        JSONObject result = new JSONObject();
        try {
            result.put("command", command);
            result.put("permission_granted", enabled(context));
            if (!enabled(context)) {
                result.put("error", "notification_listener_permission_required");
                return result;
            }
            MediaSessionManager manager = (MediaSessionManager)
                context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController controller : manager.getActiveSessions(
                    new ComponentName(context, MediaSessionBridge.class))) {
                if (!SPOTIFY.equals(controller.getPackageName())) continue;
                PlaybackState playback = controller.getPlaybackState();
                long actions = playback == null ? 0 : playback.getActions();
                long required;
                switch (command) {
                    case "play": required = PlaybackState.ACTION_PLAY; break;
                    case "pause": required = PlaybackState.ACTION_PAUSE; break;
                    case "next": required = PlaybackState.ACTION_SKIP_TO_NEXT; break;
                    case "previous": required = PlaybackState.ACTION_SKIP_TO_PREVIOUS; break;
                    default:
                        result.put("error", "unsupported_command");
                        return result;
                }
                if ((actions & required) == 0) {
                    result.put("error", "action_not_supported");
                    return result;
                }
                MediaController.TransportControls controls = controller.getTransportControls();
                switch (command) {
                    case "play": controls.play(); break;
                    case "pause": controls.pause(); break;
                    case "next": controls.skipToNext(); break;
                    case "previous": controls.skipToPrevious(); break;
                    default: break;
                }
                result.put("status", "requested");
                return result;
            }
            result.put("error", "spotify_session_not_found");
        } catch (SecurityException error) {
            try { result.put("error", "notification_listener_permission_required"); }
            catch (org.json.JSONException ignored) { }
        } catch (Exception error) {
            try { result.put("error", "media_command_failed"); }
            catch (org.json.JSONException ignored) { }
        }
        return result;
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
