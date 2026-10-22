package com.vidrio.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Notificación de reproducción (la tarjeta con portada y botones de la barra de notificaciones
 * y de la pantalla de bloqueo). El audio sigue sonando en la página web; este servicio solo
 * muestra el estado y reenvía los botones (anterior, pausa, siguiente, barra de posición) a la página.
 * Usa únicamente clases de Android, sin librerías extra.
 */
public class MediaService extends Service {

    static final String CHANNEL = "vidrio_playback";
    static final int NID = 4711;
    static final String A_UPDATE = "com.vidrio.player.UPDATE";
    static final String A_PLAY = "com.vidrio.player.PLAY";
    static final String A_PAUSE = "com.vidrio.player.PAUSE";
    static final String A_NEXT = "com.vidrio.player.NEXT";
    static final String A_PREV = "com.vidrio.player.PREV";

    static volatile MediaService inst;

    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private NotificationManager nm;
    private PowerManager.WakeLock wake;
    private boolean fg = false;

    private String title = "", artist = "", album = "", artPath = "";
    private Bitmap art = null;
    private boolean playing = false;
    private long pos = 0, dur = 0;

    private final Runnable detachRun = new Runnable() {
        @Override
        public void run() {
            // Pausado un rato: deja la notificación pero suelta el modo "primer plano".
            if (!playing && fg) {
                try {
                    stopForeground(false);
                } catch (Exception e) {
                    // nada
                }
                fg = false;
                notifyNow();
            }
        }
    };

    /* ---------------- API estática, la usa el plugin ---------------- */

    static void update(final Context c, String title, String artist, String album, String cover, boolean playing, long pos, long dur) {
        final Bundle b = new Bundle();
        b.putString("title", title == null ? "" : title);
        b.putString("artist", artist == null ? "" : artist);
        b.putString("album", album == null ? "" : album);
        b.putString("cover", cover == null ? "" : cover);
        b.putBoolean("playing", playing);
        b.putLong("pos", pos);
        b.putLong("dur", dur);
        final MediaService s = inst;
        if (s != null) {
            s.h.post(new Runnable() {
                @Override
                public void run() {
                    s.applyNow(b);
                }
            });
            return;
        }
        if (!playing) return; // no arrancamos el servicio para un estado en pausa
        Intent i = new Intent(c, MediaService.class);
        i.setAction(A_UPDATE);
        i.putExtras(b);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            // sin permiso para arrancar en segundo plano: la reproducción sigue igual
        }
    }

    static void stop(Context c) {
        final MediaService s = inst;
        if (s == null) return;
        s.h.post(new Runnable() {
            @Override
            public void run() {
                s.shutdown();
            }
        });
    }

    /* ---------------- ciclo de vida ---------------- */

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        inst = this;
        setup();
        String a = i == null ? null : i.getAction();
        if (A_UPDATE.equals(a) && i.getExtras() != null) {
            applyNow(i.getExtras());
            if (!fg) goForeground(); // Android exige llamar a startForeground si nos arrancaron como primer plano
            if (!playing) h.postDelayed(detachRun, 120000);
        } else if (a != null) {
            relay(a);
        } else if (!fg) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Se cerró la app deslizándola: la página (y el audio) ya no existen.
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        wakeOff();
        try {
            if (session != null) {
                session.setActive(false);
                session.release();
            }
        } catch (Exception e) {
            // nada
        }
        session = null;
        inst = null;
        super.onDestroy();
    }

    private void shutdown() {
        h.removeCallbacksAndMessages(null);
        try {
            stopForeground(true);
        } catch (Exception e) {
            // nada
        }
        fg = false;
        try {
            if (nm != null) nm.cancel(NID);
        } catch (Exception e) {
            // nada
        }
        stopSelf();
    }

    /* ---------------- armado ---------------- */

    private void setup() {
        if (session != null) return;
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidrio:playback");
            wake.setReferenceCounted(false);
        }
        session = new MediaSession(this, "Vidrio");
        if (Build.VERSION.SDK_INT < 33) {
            try {
                session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            } catch (Exception e) {
                // nada
            }
        }
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                MusicLibPlugin.emit("play", 0);
            }

            @Override
            public void onPause() {
                MusicLibPlugin.emit("pause", 0);
            }

            @Override
            public void onSkipToNext() {
                MusicLibPlugin.emit("next", 0);
            }

            @Override
            public void onSkipToPrevious() {
                MusicLibPlugin.emit("prev", 0);
            }

            @Override
            public void onSeekTo(long p) {
                MusicLibPlugin.emit("seek", p);
            }

            @Override
            public void onStop() {
                MusicLibPlugin.emit("stop", 0);
            }
        });
        session.setActive(true);
    }

    private void applyNow(Bundle b) {
        setup();
        title = b.getString("title", "");
        artist = b.getString("artist", "");
        album = b.getString("album", "");
        playing = b.getBoolean("playing", false);
        pos = b.getLong("pos", 0);
        dur = b.getLong("dur", 0);
        String c = b.getString("cover", "");
        if (!c.equals(artPath)) {
            artPath = c;
            art = null;
            if (!c.isEmpty()) {
                try {
                    art = BitmapFactory.decodeFile(c);
                } catch (Throwable t) {
                    art = null;
                }
            }
        }
        pushSession();
        h.removeCallbacks(detachRun);
        if (playing) {
            wakeOn();
            if (!fg) goForeground();
            else notifyNow();
        } else {
            wakeOff();
            if (fg) h.postDelayed(detachRun, 120000);
            notifyNow();
        }
    }

    private void pushSession() {
        if (session == null) return;
        try {
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
            if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(mb.build());
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
            PlaybackState ps = new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build();
            session.setPlaybackState(ps);
        } catch (Exception e) {
            // nada
        }
    }

    private PendingIntent act(String a, int code) {
        Intent i = new Intent(this, MediaService.class);
        i.setAction(a);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, code, i, fl);
    }

    private Notification build() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL);
        else b = new Notification.Builder(this);

        int small = getResources().getIdentifier("ic_stat_music", "drawable", getPackageName());
        if (small == 0) small = android.R.drawable.ic_media_play;

        b.setSmallIcon(small)
            .setContentTitle(title)
            .setContentText(artist)
            .setOngoing(playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (album != null && !album.isEmpty()) b.setSubText(album);
        if (art != null) b.setLargeIcon(art);

        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
            b.setContentIntent(PendingIntent.getActivity(this, 0, open, fl));
        }

        b.addAction(android.R.drawable.ic_media_previous, "Anterior", act(A_PREV, 1));
        if (playing) b.addAction(android.R.drawable.ic_media_pause, "Pausar", act(A_PAUSE, 2));
        else b.addAction(android.R.drawable.ic_media_play, "Reproducir", act(A_PLAY, 3));
        b.addAction(android.R.drawable.ic_media_next, "Siguiente", act(A_NEXT, 4));

        Notification.MediaStyle st = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (session != null) st.setMediaSession(session.getSessionToken());
        b.setStyle(st);
        return b.build();
    }

    private void notifyNow() {
        try {
            if (nm != null) nm.notify(NID, build());
        } catch (Exception e) {
            // nada
        }
    }

    private void goForeground() {
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NID, n);
            }
            fg = true;
        } catch (Exception e) {
            // Android no deja pasar a primer plano desde segundo plano: dejamos solo la notificación
            fg = false;
            try {
                nm.notify(NID, n);
            } catch (Exception e2) {
                // nada
            }
        }
    }

    private void relay(String a) {
        if (MusicLibPlugin.inst == null) {
            // la app ya no está viva: no hay a quién avisar
            shutdown();
            return;
        }
        if (A_PLAY.equals(a)) MusicLibPlugin.emit("play", 0);
        else if (A_PAUSE.equals(a)) MusicLibPlugin.emit("pause", 0);
        else if (A_NEXT.equals(a)) MusicLibPlugin.emit("next", 0);
        else if (A_PREV.equals(a)) MusicLibPlugin.emit("prev", 0);
    }

    private void wakeOn() {
        try {
            if (wake != null && !wake.isHeld()) wake.acquire(6L * 60 * 60 * 1000);
        } catch (Exception e) {
            // nada
        }
    }

    private void wakeOff() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception e) {
            // nada
        }
    }
}
