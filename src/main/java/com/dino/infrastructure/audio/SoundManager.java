package com.dino.infrastructure.audio;

import com.dino.application.services.EventBus;
import com.dino.domain.events.EventNames;

import javax.sound.sampled.*;

public class SoundManager {

    public SoundManager(EventBus eventBus) {
        eventBus.subscribe(EventNames.GAME_STARTED,    e -> playTone(440, 300));
        eventBus.subscribe(EventNames.ITEM_COLLECTED,  e -> playTone(660, 150));
        eventBus.subscribe(EventNames.VIRUS_TRIGGERED, e -> playTone(220, 400));
        eventBus.subscribe(EventNames.PLAYER_CONSUMED, e -> playTone(520, 200));
        eventBus.subscribe(EventNames.GAME_OVER,       e -> playTone(330, 800));
    }

    private void playTone(int frequency, int durationMs) {
        Thread.ofVirtual().start(() -> {
            try {
                float sampleRate = 44100f;
                int numSamples = (int) (sampleRate * durationMs / 1000.0);
                byte[] buffer = new byte[numSamples * 2];
                for (int i = 0; i < numSamples; i++) {
                    double angle = 2.0 * Math.PI * i * frequency / sampleRate;
                    short sample = (short) (Math.sin(angle) * 32767 * 0.5);
                    buffer[i * 2]     = (byte) (sample & 0xFF);
                    buffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
                }
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(Clip.class, format);
                if (!AudioSystem.isLineSupported(info)) return;
                Clip clip = (Clip) AudioSystem.getLine(info);
                clip.open(format, buffer, 0, buffer.length);
                clip.start();
                Thread.sleep(durationMs + 50);
                clip.close();
            } catch (Exception ignored) {}
        });
    }
}
