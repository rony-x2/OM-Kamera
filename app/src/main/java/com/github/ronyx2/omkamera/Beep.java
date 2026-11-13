package com.github.ronyx2.omkamera;

import android.media.MediaPlayer;

/**
 * Plays the camera's own system sounds. The camera provides them as media URIs
 * ("SCALAR_A://" followed by the sound name), so they sound like the camera's own beeps.
 */
public class Beep {
    public static final String FOCUS_LOCK = "focuslock";

    private MediaPlayer player;
    private boolean prepared;

    public void prepare(String sound) {
        release();
        try {
            player = new MediaPlayer();
            player.setDataSource("SCALAR_A://" + sound);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                }
            });
            player.prepareAsync();
        } catch (Throwable t) {
            Log.error("beep prepare " + sound, t);
            player = null;
        }
    }

    public void play() {
        if (player == null || !prepared) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.seekTo(0);
            } else {
                player.start();
            }
        } catch (Throwable t) {
            Log.error("beep play", t);
        }
    }

    public void release() {
        prepared = false;
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }
}
