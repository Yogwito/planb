package com.dino.infrastructure.audio;

import com.dino.application.services.EventBus;
import com.dino.domain.events.EventNames;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;

public class SoundManager {

    public SoundManager(EventBus eventBus) {
        eventBus.subscribe(EventNames.GAME_STARTED, e -> playTone(440, 220));
        eventBus.subscribe(EventNames.BUTTON_STATE_CHANGED, e -> playTone(620, 120));
        eventBus.subscribe(EventNames.SCORE_CHANGED, e -> playTone(700, 100));
        eventBus.subscribe(EventNames.PLAYER_REACHED_EXIT, e -> playTone(820, 180));
        eventBus.subscribe(EventNames.ROOM_RESET, e -> playTone(240, 260));
        eventBus.subscribe(EventNames.LEVEL_COMPLETED, e -> playTone(740, 450));
        eventBus.subscribe(EventNames.GAME_OVER, e -> playTone(520, 600));
    }

    private void playTone(int frequency, int durationMs) {
        Thread.ofVirtual().start(() -> {
            try {
                float sampleRate = 44100f;
                int numSamples = (int) (sampleRate * durationMs / 1000.0);
                byte[] buffer = new byte[numSamples * 2];
                for (int i = 0; i < numSamples; i++) {
                    double angle = 2.0 * Math.PI * i * frequency / sampleRate;
                    short sample = (short) (Math.sin(angle) * 32767 * 0.45);
                    buffer[i * 2] = (byte) (sample & 0xFF);
                    buffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
                }
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(Clip.class, format);
                if (!AudioSystem.isLineSupported(info)) return;
                Clip clip = (Clip) AudioSystem.getLine(info);
                clip.open(format, buffer, 0, buffer.length);
                clip.start();
                Thread.sleep(durationMs + 50L);
                clip.close();
            } catch (Exception ignored) {
            }
        });
    }
}
