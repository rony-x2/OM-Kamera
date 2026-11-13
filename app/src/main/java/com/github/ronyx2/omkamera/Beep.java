package com.github.ronyx2.omkamera;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;

/**
 * Focus confirmation sound. Either the camera's own system sound, which the camera provides as
 * media URI ("SCALAR_A://" followed by the sound id, e.g. BEEP_ID_FOCUSLOCK), or a generated tone.
 */
public class Beep {
    public static final String FOCUS_LOCK = "BEEP_ID_FOCUSLOCK";

    public enum Mode { OFF, CAMERA, TONE }

    private MediaPlayer player;
    private boolean prepared;
    private ToneGenerator tone;

    public void prepare(String sound) {
        release();
        try {
            player = new MediaPlayer();
            player.setDataSource("SCALAR_A://" + sound);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    Log.info("beep prepared");
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.info("beep error what=" + what + " extra=" + extra);
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Throwable t) {
            Log.error("beep prepare " + sound, t);
            player = null;
        }
        try {
            tone = new ToneGenerator(AudioManager.STREAM_SYSTEM, 80);
        } catch (Throwable t) {
            Log.error("tone generator", t);
            tone = null;
        }
    }

    public void play(Mode mode) {
        if (mode == Mode.OFF) {
            return;
        }
        if (mode == Mode.CAMERA && player != null && prepared) {
            try {
                if (player.isPlaying()) {
                    player.seekTo(0);
                } else {
                    player.start();
                }
                Log.info("beep camera");
                return;
            } catch (Throwable t) {
                Log.error("beep play", t);
            }
        }
        // Generated tone, also as fallback if the camera sound is not ready
        if (tone != null) {
            try {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
                Log.info("beep tone" + (mode == Mode.CAMERA ? " (camera sound not ready)" : ""));
            } catch (Throwable t) {
                Log.error("tone", t);
            }
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
        if (tone != null) {
            try {
                tone.release();
            } catch (Throwable ignored) {
            }
            tone = null;
        }
    }
}
