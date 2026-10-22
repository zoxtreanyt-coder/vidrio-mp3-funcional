package com.vidrio.player;

import android.Manifest;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Window;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import org.json.JSONObject;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.File;
import java.io.FileOutputStream;

@CapacitorPlugin(
    name = "MusicLib",
    permissions = {
        @Permission(alias = "audio", strings = { "android.permission.READ_MEDIA_AUDIO" }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class MusicLibPlugin extends Plugin {

    /** Tamaño de las barras del sistema en px de CSS: arriba, derecha, abajo, izquierda. Lo llena MainActivity. */
    public static volatile float[] INSETS = new float[] { 0f, 0f, 0f, 0f };

    static volatile MusicLibPlugin inst;

    @Override
    public void load() {
        inst = this;
    }

    @Override
    protected void handleOnDestroy() {
        MediaService.stop(getContext());
        if (inst == this) inst = null;
        super.handleOnDestroy();
    }

    /** Avisa a la página de un botón de la notificación (play, pause, next, prev, seek, stop). */
    static void emit(String action, long pos) {
        MusicLibPlugin p = inst;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("action", action);
        o.put("pos", pos);
        p.notifyListeners("mediaAction", o);
    }

    @PluginMethod
    public void getInsets(PluginCall call) {
        float[] i = INSETS;
        JSObject r = new JSObject();
        r.put("t", (double) i[0]);
        r.put("r", (double) i[1]);
        r.put("b", (double) i[2]);
        r.put("l", (double) i[3]);
        call.resolve(r);
    }

    /** Íconos de la barra de estado y de gestos claros u oscuros según el modo día/noche. */
    @PluginMethod
    public void setBars(PluginCall call) {
        final boolean light = call.getData().optBoolean("light", false);
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Window w = getActivity().getWindow();
                        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
                        c.setAppearanceLightStatusBars(light);
                        c.setAppearanceLightNavigationBars(light);
                    } catch (Exception e) {
                        // nada
                    }
                }
            });
        }
        call.resolve();
    }

    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        requestPermissionForAlias("notifications", call, "notifResult");
    }

    @PermissionCallback
    private void notifResult(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("notifications") == PermissionState.GRANTED);
        call.resolve(r);
    }

    /** Actualiza la notificación de reproducción (título, artista, portada, play/pausa, posición). */
    @PluginMethod
    public void mediaUpdate(PluginCall call) {
        JSONObject d = call.getData();
        MediaService.update(
            getContext(),
            d.optString("title", ""),
            d.optString("artist", ""),
            d.optString("album", ""),
            d.optString("cover", ""),
            d.optBoolean("playing", false),
            d.optLong("pos", 0),
            d.optLong("dur", 0)
        );
        call.resolve();
    }

    @PluginMethod
    public void mediaStop(PluginCall call) {
        MediaService.stop(getContext());
        call.resolve();
    }

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    private boolean granted() {
        return getPermissionState(alias()) == PermissionState.GRANTED;
    }

    @PluginMethod
    public void checkAccess(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", granted());
        call.resolve(r);
    }

    @PluginMethod
    public void requestAccess(PluginCall call) {
        if (granted()) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        requestPermissionForAlias(alias(), call, "accessResult");
    }

    @PermissionCallback
    private void accessResult(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", granted());
        call.resolve(r);
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (!granted()) {
            call.reject("Sin permiso");
            return;
        }
        JSArray out = new JSArray();
        String[] proj = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        };
        String sel = "(" + MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ? OR " + MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?)";
        String[] args = { "%.mp3", "%.m4a" };
        try (Cursor c = getContext().getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    JSObject o = new JSObject();
                    o.put("id", c.getLong(0));
                    o.put("name", c.getString(1));
                    o.put("title", c.getString(2));
                    o.put("artist", c.getString(3));
                    o.put("album", c.getString(4));
                    o.put("dur", c.getLong(5) / 1000.0);
                    o.put("path", c.getString(6));
                    o.put("size", c.getLong(7));
                    o.put("mtime", c.getLong(8));
                    out.put(o);
                }
            }
        } catch (Exception e) {
            call.reject("No se pudo leer la música: " + e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("tracks", out);
        call.resolve(r);
    }

    @PluginMethod
    public void cover(PluginCall call) {
        String path = call.getString("path");
        String key = call.getString("key");
        JSObject r = new JSObject();
        if (path == null || key == null) {
            call.resolve(r);
            return;
        }
        File dir = new File(getContext().getCacheDir(), "covers");
        dir.mkdirs();
        File out = new File(dir, key + ".jpg");
        File none = new File(dir, key + ".none");
        if (out.exists()) {
            r.put("path", out.getAbsolutePath());
            call.resolve(r);
            return;
        }
        if (none.exists()) {
            call.resolve(r);
            return;
        }
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            byte[] pic = mmr.getEmbeddedPicture();
            if (pic != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= 400) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                Bitmap b = BitmapFactory.decodeByteArray(pic, 0, pic.length, o2);
                if (b != null) {
                    Bitmap sc = b;
                    if (b.getWidth() > 400) {
                        sc = Bitmap.createScaledBitmap(b, 400, Math.max(1, 400 * b.getHeight() / b.getWidth()), true);
                    }
                    FileOutputStream fo = new FileOutputStream(out);
                    sc.compress(Bitmap.CompressFormat.JPEG, 82, fo);
                    fo.close();
                    r.put("path", out.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            // sin portada
        } finally {
            try {
                mmr.release();
            } catch (Exception e) {
                // nada
            }
        }
        if (!r.has("path")) {
            try {
                none.createNewFile();
            } catch (Exception e) {
                // nada
            }
        }
        call.resolve(r);
    }
}
