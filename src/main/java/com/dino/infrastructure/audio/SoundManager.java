package com.dino.infrastructure.audio;

import com.dino.application.services.EventBus;
import com.dino.domain.events.EventNames;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;

public class SoundManager {
    private static final float SAMPLE_RATE = 44100f;

    public SoundManager(EventBus eventBus) {
        eventBus.subscribe(EventNames.GAME_STARTED, e -> playCartoonChord(new int[]{392, 523}, 180, 0.34, 0.10));
        eventBus.subscribe(EventNames.PLAYER_JUMPED, e -> playCartoonBlip(620, 920, 100, 0.32));
        eventBus.subscribe(EventNames.PLAYER_COLLIDED, e -> playCartoonBlip(220, 170, 75, 0.30));
        eventBus.subscribe(EventNames.THREAD_STRETCHED, e -> playCartoonBlip(340, 470, 90, 0.22));
        eventBus.subscribe(EventNames.BUTTON_STATE_CHANGED, e -> playCartoonChord(new int[]{660, 880}, 110, 0.24, 0.05));
        eventBus.subscribe(EventNames.SCORE_CHANGED, e -> playCartoonBlip(720, 940, 95, 0.28));
        eventBus.subscribe(EventNames.PLAYER_REACHED_EXIT, e -> playCartoonChord(new int[]{784, 988}, 180, 0.30, 0.14));
        eventBus.subscribe(EventNames.ROOM_RESET, e -> playCartoonBlip(260, 150, 190, 0.30));
        eventBus.subscribe(EventNames.LEVEL_ADVANCED, e -> playCartoonChord(new int[]{440, 587, 740}, 240, 0.30, 0.16));
        eventBus.subscribe(EventNames.LEVEL_COMPLETED, e -> playCartoonChord(new int[]{523, 659, 784}, 320, 0.34, 0.18));
        eventBus.subscribe(EventNames.GAME_OVER, e -> playCartoonChord(new int[]{392, 330}, 420, 0.28, 0.08));
    }

    private void playCartoonBlip(int startFrequency, int endFrequency, int durationMs, double volume) {
        Thread.ofPlatform().daemon(true).start(() -> playBuffer(buildGlideBuffer(startFrequency, endFrequency, durationMs, volume, true), durationMs));
    }

    private void playCartoonChord(int[] frequencies, int durationMs, double volume, double vibratoDepth) {
        Thread.ofPlatform().daemon(true).start(() -> playBuffer(buildChordBuffer(frequencies, durationMs, volume, vibratoDepth), durationMs));
    }

    private byte[] buildGlideBuffer(int startFrequency, int endFrequency, int durationMs, double volume, boolean bouncyEnvelope) {
        int numSamples = (int) (SAMPLE_RATE * durationMs / 1000.0);
        byte[] buffer = new byte[numSamples * 2];
        double phase = 0;
        for (int i = 0; i < numSamples; i++) {
            double t = i / (double) Math.max(1, numSamples - 1);
            double eased = 1.0 - Math.pow(1.0 - t, 2.2);
            double frequency = startFrequency + (endFrequency - startFrequency) * eased;
            phase += 2.0 * Math.PI * frequency / SAMPLE_RATE;

            double envelope = bouncyEnvelope ? bounceEnvelope(t) : softEnvelope(t);
            double wave = Math.sin(phase) * 0.78 + Math.sin(phase * 2.0) * 0.16 + Math.sin(phase * 3.0) * 0.06;
            short sample = (short) (wave * envelope * volume * 32767);
            buffer[i * 2] = (byte) (sample & 0xFF);
            buffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buffer;
    }

    private byte[] buildChordBuffer(int[] frequencies, int durationMs, double volume, double vibratoDepth) {
        int numSamples = (int) (SAMPLE_RATE * durationMs / 1000.0);
        byte[] buffer = new byte[numSamples * 2];
        double[] phases = new double[frequencies.length];

        for (int i = 0; i < numSamples; i++) {
            double t = i / (double) Math.max(1, numSamples - 1);
            double envelope = softEnvelope(t);
            double vibrato = 1.0 + Math.sin(2.0 * Math.PI * t * 6.0) * vibratoDepth * 0.06;

            double wave = 0;
            for (int j = 0; j < frequencies.length; j++) {
                phases[j] += 2.0 * Math.PI * (frequencies[j] * vibrato) / SAMPLE_RATE;
                wave += Math.sin(phases[j]);
            }
            wave /= Math.max(1, frequencies.length);
            wave += Math.sin(phases[0] * 2.0) * 0.08;

            short sample = (short) (wave * envelope * volume * 32767);
            buffer[i * 2] = (byte) (sample & 0xFF);
            buffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buffer;
    }

    private double bounceEnvelope(double t) {
        if (t < 0.10) return t / 0.10;
        double decay = Math.pow(1.0 - t, 1.35);
        return Math.max(0, decay * (0.92 + Math.sin(t * 14.0) * 0.08));
    }

    private double softEnvelope(double t) {
        double attack = Math.min(1.0, t / 0.08);
        double release = Math.min(1.0, (1.0 - t) / 0.22);
        return attack * release;
    }

    private void playBuffer(byte[] buffer, int durationMs) {
        if (AudioSystem.getMixerInfo().length == 0) return;
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(Clip.class, format);
        if (!AudioSystem.isLineSupported(info)) return;
        Clip clip;
        try {
            clip = (Clip) AudioSystem.getLine(info);
            clip.open(format, buffer, 0, buffer.length);
        } catch (Exception e) {
            System.err.println("[Sound] " + e.getMessage());
            return;
        }
        try {
            clip.start();
            Thread.sleep(durationMs + 50L);
        } catch (Exception e) {
            System.err.println("[Sound] " + e.getMessage());
        } finally {
            clip.close();
        }
    }
}
