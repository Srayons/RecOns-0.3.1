package com.subtitlerecorder.core;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频输入设备包装类
 */
public class TargetDataLineWrapper implements TargetDataLine {
    private TargetDataLine line;
    private int sampleRate;
    private int channels;

    public TargetDataLineWrapper(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /**
     * 获取可用的音频输入设备列表
     */
    public static List<Mixer.Info> getAvailableMicrophones() {
        List<Mixer.Info> microphones = new ArrayList<>();
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();

        for (Mixer.Info mixerInfo : mixers) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            Line.Info[] lineInfos = mixer.getTargetLineInfo(new Line.Info(TargetDataLine.class));
            if (lineInfos.length > 0) {
                microphones.add(mixerInfo);
            }
        }
        return microphones;
    }

    private TargetDataLine getLine() throws LineUnavailableException {
        if (line != null) {
            return line;
        }

        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,    // 16bit
                channels,
                channels * 2, // 帧大小
                sampleRate,
                false  // 小端序
        );

        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            // 尝试查找可用的格式
            for (Mixer.Info mixerInfo : getAvailableMicrophones()) {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                try {
                    line = (TargetDataLine) mixer.getLine(info);
                    if (line != null) {
                        break;
                    }
                } catch (LineUnavailableException e) {
                    // 尝试下一个设备
                }
            }
        }

        if (line == null) {
            line = (TargetDataLine) AudioSystem.getLine(info);
        }

        return line;
    }

    @Override
    public void open(AudioFormat format) throws LineUnavailableException {
        getLine().open(format);
    }

    @Override
    public void open(AudioFormat format, int bufferSize) throws LineUnavailableException {
        getLine().open(format, bufferSize);
    }

    @Override
    public void open() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false
        );
        getLine().open(format);
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (line == null) {
            return 0;
        }
        return line.read(b, off, len);
    }

    @Override
    public void start() {
        if (line != null) {
            line.start();
        }
    }

    @Override
    public void stop() {
        if (line != null) {
            line.stop();
        }
    }

    @Override
    public void close() {
        if (line != null) {
            line.close();
        }
    }

    @Override
    public void drain() {
        if (line != null) line.drain();
    }

    @Override
    public void flush() {
        if (line != null) line.flush();
    }

    @Override
    public int available() {
        return line != null ? line.available() : 0;
    }

    @Override
    public int getBufferSize() {
        return line != null ? line.getBufferSize() : 0;
    }

    @Override
    public AudioFormat getFormat() {
        return line != null ? line.getFormat() : null;
    }

    @Override
    public int getFramePosition() {
        return line != null ? line.getFramePosition() : 0;
    }

    @Override
    public float getLevel() {
        return line != null ? line.getLevel() : 0;
    }

    @Override
    public long getLongFramePosition() {
        return line != null ? line.getLongFramePosition() : 0;
    }

    @Override
    public long getMicrosecondPosition() {
        return line != null ? line.getMicrosecondPosition() : 0;
    }

    @Override
    public boolean isActive() {
        return line != null && line.isActive();
    }

    @Override
    public boolean isRunning() {
        return line != null && line.isRunning();
    }

    @Override
    public boolean isOpen() {
        return line != null && line.isOpen();
    }

    @Override
    public void addLineListener(LineListener listener) {
        if (line != null) line.addLineListener(listener);
    }

    @Override
    public void removeLineListener(LineListener listener) {
        if (line != null) line.removeLineListener(listener);
    }

    @Override
    public Line.Info getLineInfo() {
        return line != null ? line.getLineInfo() : null;
    }

    @Override
    public javax.sound.sampled.Control[] getControls() {
        return line != null ? line.getControls() : new Control[0];
    }

    @Override
    public boolean isControlSupported(javax.sound.sampled.Control.Type control) {
        return line != null && line.isControlSupported(control);
    }

    @Override
    public javax.sound.sampled.Control getControl(javax.sound.sampled.Control.Type control) {
        return line != null ? line.getControl(control) : null;
    }
}
