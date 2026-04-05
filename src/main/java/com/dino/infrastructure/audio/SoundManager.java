package com.dino.infrastructure.audio;

import com.dino.application.services.EventBus;
import com.dino.domain.events.EventNames;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gestor de audio procedural del juego.
 *
 * <p>No depende de archivos externos. Escucha eventos del {@link EventBus} y
 * genera tonos y acordes sencillos con Java Sound para maximizar portabilidad
 * en distintos equipos de laboratorio.</p>
 */
public class SoundManager {
    private static final float SAMPLE_RATE = 44100f;
    private static final int MUSIC_CHUNK_MS = 180;
    private final AtomicBoolean musicRunning = new AtomicBoolean(false);
    private Thread musicThread;

    /**
     * Registra las suscripciones del sistema de audio a los eventos del juego.
     *
     * @param eventBus bus de eventos compartido
     */
    public SoundManager(EventBus eventBus) {
        eventBus.subscribe(EventNames.GAME_STARTED, e -> {
            startBackgroundMusic();
            playCartoonChord(new int[]{392, 523}, 180, 0.34, 0.10);
        });
        eventBus.subscribe(EventNames.PLAYER_JUMPED, e -> playCartoonBlip(620, 920, 100, 0.32));
        eventBus.subscribe(EventNames.PLAYER_COLLIDED, e -> playCartoonBlip(220, 170, 75, 0.30));
        eventBus.subscribe(EventNames.THREAD_STRETCHED, e -> playCartoonBlip(340, 470, 90, 0.22));
        eventBus.subscribe(EventNames.BUTTON_STATE_CHANGED, e -> playCartoonChord(new int[]{660, 880}, 110, 0.24, 0.05));
        eventBus.subscribe(EventNames.SCORE_CHANGED, e -> playCartoonBlip(720, 940, 95, 0.28));
        eventBus.subscribe(EventNames.PLAYER_REACHED_EXIT, e -> playCartoonChord(new int[]{784, 988}, 180, 0.30, 0.14));
        eventBus.subscribe(EventNames.ROOM_RESET, e -> playCartoonBlip(260, 150, 190, 0.30));
        eventBus.subscribe(EventNames.LEVEL_ADVANCED, e -> playCartoonChord(new int[]{440, 587, 740}, 240, 0.30, 0.16));
        eventBus.subscribe(EventNames.LEVEL_COMPLETED, e -> playCartoonChord(new int[]{523, 659, 784}, 320, 0.34, 0.18));
        eventBus.subscribe(EventNames.GAME_OVER, e -> {
            stopBackgroundMusic();
            playCartoonChord(new int[]{392, 330}, 420, 0.28, 0.08);
        });
        eventBus.subscribe(EventNames.COIN_COLLECTED, e -> playCartoonBlip(900, 1300, 75, 0.26));
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

    private void startBackgroundMusic() {
        if (musicRunning.getAndSet(true)) return;
        musicThread = Thread.ofPlatform().daemon(true).start(this::musicLoop);
    }

    private void musicLoop() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        SourceDataLine line = null;
        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, (int) (SAMPLE_RATE * 2));
            line.start();

            int[][] progression = new int[][]{
                {392, 494, 587},
                {440, 523, 659},
                {349, 440, 523},
                {392, 494, 587}
            };
            int[] melody = new int[]{587, 659, 698, 659, 587, 523, 494, 523};
            int chordIndex = 0;
            int melodyIndex = 0;

            while (musicRunning.get()) {
                byte[] chunk = buildMusicChunk(progression[chordIndex], melody[melodyIndex], MUSIC_CHUNK_MS, 0.12);
                line.write(chunk, 0, chunk.length);
                chordIndex = (chordIndex + 1) % progression.length;
                melodyIndex = (melodyIndex + 1) % melody.length;
            }
            line.drain();
        } catch (Exception e) {
            System.err.println("[Sound] music: " + e.getMessage());
        } finally {
            if (line != null) {
                try {
                    line.stop();
                } catch (Exception ignored) {
                }
                line.close();
            }
            musicRunning.set(false);
        }
    }

    private byte[] buildMusicChunk(int[] chord, int melodyFrequency, int durationMs, double volume) {
        int numSamples = (int) (SAMPLE_RATE * durationMs / 1000.0);
        byte[] buffer = new byte[numSamples * 2];
        double[] chordPhases = new double[chord.length];
        double melodyPhase = 0;
        double bassPhase = 0;
        int bassFrequency = Math.max(110, chord[0] / 2);

        for (int i = 0; i < numSamples; i++) {
            double t = i / (double) Math.max(1, numSamples - 1);
            double envelope = 0.75 + Math.sin(Math.PI * t) * 0.25;
            double chordWave = 0;
            for (int j = 0; j < chord.length; j++) {
                chordPhases[j] += 2.0 * Math.PI * chord[j] / SAMPLE_RATE;
                chordWave += Math.sin(chordPhases[j]) * 0.33;
            }
            melodyPhase += 2.0 * Math.PI * melodyFrequency / SAMPLE_RATE;
            bassPhase += 2.0 * Math.PI * bassFrequency / SAMPLE_RATE;

            double wave = chordWave * 0.56
                + Math.sin(melodyPhase) * 0.26
                + Math.sin(melodyPhase * 2.0) * 0.05
                + Math.sin(bassPhase) * 0.18;
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
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            line.open(format, buffer.length);
            line.start();
            line.write(buffer, 0, buffer.length);
            line.drain();
            line.stop();
            line.close();
        } catch (Exception e) {
            System.err.println("[Sound] play: " + e.getMessage());
        }
    }

    /**
     * Libera el audio continuo al cerrar la aplicación o volver al menú.
     */
    public void close() {
        stopBackgroundMusic();
    }

    private void stopBackgroundMusic() {
        musicRunning.set(false);
        if (musicThread != null) {
            try {
                musicThread.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            musicThread = null;
        }
    }
}
