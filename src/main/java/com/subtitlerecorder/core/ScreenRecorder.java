package com.subtitlerecorder.core;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.subtitlerecorder.asr.AudioDispatcher;

import javax.sound.sampled.TargetDataLine;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 屏幕录制器 - 负责屏幕画面采集和音视频编码
 * 音频从外部传入的TargetDataLine获取，格式统一为16kHz 16bit 单声道（Vosk要求的格式）
 */
public class ScreenRecorder {
    private static final Logger logger = LoggerFactory.getLogger(ScreenRecorder.class);

    private FFmpegFrameRecorder recorder;
    private Robot robot;
    private Rectangle captureArea;
    private String outputFilePath;
    private int captureWidth;
    private int captureHeight;

    // private TargetDataLine audioLine;
    private AudioDispatcher audioDispatcher;
    private BlockingQueue<byte[]> audioQueue;

    private ScheduledExecutorService frameScheduler;
    private ExecutorService audioExecutor;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    private long startTime;
    private long pausedDuration = 0;
    private long pauseStartTime = 0;

    private Java2DFrameConverter frameConverter;

    private static final int VIDEO_FPS = 30;
    private static final int VIDEO_BITRATE = 4000000; // 4Mbps
    private static final int AUDIO_SAMPLE_RATE = 16000; // Vosk统一格式
    private static final int AUDIO_CHANNELS = 1;
    private static final int AUDIO_BITRATE = 128000;

    /**
     * 构造函数
     * 
     * @param x          录制区域起始X
     * @param y          录制区域起始Y
     * @param width      录制区域宽度
     * @param height     录制区域高度
     * @param outputPath 输出MP4文件路径
     * @param audioLine  已打开的音频线路（由RecordingManager管理）
     */
    public ScreenRecorder(int x, int y, int width, int height, String outputPath, AudioDispatcher audioDispatcher)
            throws AWTException {
        this.captureArea = new Rectangle(x, y, width, height);
        this.outputFilePath = outputPath;
        this.audioDispatcher = audioDispatcher;
        this.captureHeight = makeEven(height);
        this.captureWidth = makeEven(width);
        this.frameConverter = new Java2DFrameConverter();
        this.robot = new Robot();

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
    }

    private static int makeEven(int value) {
        return (value >> 1) << 1;
    }

    /**
     * 开始录制
     */
    public boolean start() {
        if (isRunning.get())
            return false;

        try {
            initRecorder();

            isRunning.set(true);
            isPaused.set(false);
            startTime = System.currentTimeMillis();
            pausedDuration = 0;

            startVideoCapture();
            startAudioCapture();

            logger.info("屏幕录制已开始，区域: {}x{}+{}+{}", captureWidth, captureHeight, captureArea.x, captureArea.y);
            return true;

        } catch (Exception e) {
            logger.error("启动录制失败", e);
            return false;
        }
    }

    /**
     * 初始化录屏参数
     * 
     * @throws Exception
     */
    private void initRecorder() throws Exception {
        recorder = new FFmpegFrameRecorder(outputFilePath, captureWidth, captureHeight);
        recorder.setFormat("mp4");

        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setVideoBitrate(VIDEO_BITRATE);
        recorder.setFrameRate(VIDEO_FPS);
        recorder.setVideoOption("preset", "ultrafast");
        recorder.setVideoOption("tune", "zerolatency");
        recorder.setGopSize(VIDEO_FPS * 2);

        recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        recorder.setAudioChannels(AUDIO_CHANNELS);
        recorder.setSampleRate(AUDIO_SAMPLE_RATE);
        recorder.setAudioBitrate(AUDIO_BITRATE);
        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);

        recorder.start();
        logger.info("FFmpeg录制器初始化完成, 视频: {}x{}, 音频: {}Hz {}ch",
                captureWidth, captureHeight, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
    }

    /**
     * 启动视频帧采集
     */
    private void startVideoCapture() {
        frameScheduler = Executors.newSingleThreadScheduledExecutor();
        long frameInterval = 1000 / VIDEO_FPS;

        frameScheduler.scheduleAtFixedRate(() -> {
            if (!isRunning.get() || isPaused.get()) {
                return;
            }

            try {
                long captureTime = System.currentTimeMillis();
                BufferedImage screenCapture = robot.createScreenCapture(captureArea);

                if (screenCapture.getWidth() != captureWidth || screenCapture.getHeight() != captureHeight) {
                    BufferedImage resized = new BufferedImage(captureArea.width, captureArea.height,
                            BufferedImage.TYPE_3BYTE_BGR);
                    java.awt.Graphics2D g = resized.createGraphics();
                    g.drawImage(screenCapture, 0, 0, captureArea.width, captureArea.height, null);
                    g.dispose();
                    screenCapture = resized;
                }

                Frame frame = frameConverter.convert(screenCapture);

                if (frame != null) {
                    synchronized (recorder) {
                        long timestamp = (captureTime - startTime - pausedDuration) * 1000;
                        recorder.setTimestamp(timestamp);
                        recorder.record(frame);
                    }
                }
            } catch (Exception e) {
                logger.error("视频帧捕获失败", e);
                e.printStackTrace();
            }
        }, 0, frameInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * 启动音频采集与编码（无声）
     */
    private void startAudioCaptureOld() {
        // 注册消费者
        audioQueue = audioDispatcher.registerConsumer();
        audioExecutor = Executors.newSingleThreadExecutor();

        audioExecutor.submit(() -> {
            // byte[] buffer = new byte[4096];
            try {
                while (isRunning.get()) {
                    if (isPaused.get()) {
                        Thread.sleep(1100);
                        continue;
                    }

                    // if (!audioLine.isOpen() || !audioLine.isActive()) {
                    //     Thread.sleep(3000);
                    //     continue;
                    // }

                    byte[] data = audioQueue.poll(200, TimeUnit.MILLISECONDS);

                    if (data != null && data.length > 0) {
                        // int bytesRead = audioLine.read(data, 0, data.length);
                        // if (bytesRead > 0 && data.length > 0) {
                        //     synchronized (recorder) {
                        //         recordAudioData(data, data.length);
                        //     }
                        // }
                    }
                }
            } catch (Exception e) {
                if (isRunning.get()) {
                    logger.error("音频捕获失败", e);
                }
            }
        });
    }

    private void startAudioCapture() {
        audioQueue = audioDispatcher.registerConsumer();
        audioExecutor = Executors.newSingleThreadExecutor();

        audioExecutor.submit(() -> {
            boolean firstPacket = true;
            try {
                while (isRunning.get()) {
                    if (isPaused.get()) {
                        Thread.sleep(10);
                        continue;
                    }

                    // if (!audioLine.isOpen() || !audioLine.isActive()) {
                    //     Thread.sleep(3000);
                    //     continue;
                    // }

                    byte[] data = audioQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (data != null && data.length > 0) {
                        if (firstPacket) {
                            logger.info("ScreenRecorder 收到第一个音频包，大小: {} bytes", data.length);
                            firstPacket = false;
                        }
                        synchronized (recorder) {
                            recordAudioData(data, data.length);
                        }
                    }
                }
            } catch (Exception e) {
                if (isRunning.get()) {
                    logger.error("音频捕获失败", e);
                }
            }
        });
    }

    private void recordAudioData(byte[] data, int length) {
        try {
            int sampleCount = length / 2;

            ShortBuffer shortBuffer = ByteBuffer.wrap(data, 0, length)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer();

            float[] floatSamples = new float[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                floatSamples[i] = shortBuffer.get(i) / 32768.0f;
            }

            Frame audioFrame = new Frame();
            audioFrame.sampleRate = AUDIO_SAMPLE_RATE;
            audioFrame.audioChannels = AUDIO_CHANNELS;
            audioFrame.samples = new Buffer[] { ByteBuffer.allocateDirect(sampleCount * 4)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floatSamples).flip() };

            long timestamp = (System.currentTimeMillis() - startTime - pausedDuration) * 1000;
            recorder.setTimestamp(timestamp);
            recorder.record(audioFrame);
        } catch (Exception e) {
            logger.error("音频录制失败", e);
        }
    }

    /**
     * 暂停录制
     */
    public void pause() {
        if (!isRunning.get() || isPaused.get())
            return;

        isPaused.set(true);
        pauseStartTime = System.currentTimeMillis();
        logger.info("录制已暂停");
    }

    /**
     * 恢复录制
     */
    public void resume() {
        if (!isRunning.get() || !isPaused.get())
            return;

        pausedDuration += System.currentTimeMillis() - pauseStartTime;
        isPaused.set(false);
        logger.info("录制已恢复");
    }

    /**
     * 停止录制
     */
    public void stop() {
        if (!isRunning.get())
            return;

        isRunning.set(false);

        // 关闭线程池
        if (frameScheduler != null) {
            frameScheduler.shutdown();
            try {
                frameScheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (audioExecutor != null) {
            audioExecutor.shutdown();
            try {
                audioExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 关闭录制器
        try {
            synchronized (recorder) {
                recorder.stop();
                recorder.release();
            }
            logger.info("录制已停止，文件保存至: {}", outputFilePath);
        } catch (Exception e) {
            logger.error("关闭录制器失败", e);
        }
    }

    public boolean isRecording() {
        return isRunning.get();
    }

    public boolean isPaused() {
        return isPaused.get();
    }

    public String getOutputFilePath() {
        return outputFilePath;
    }
}
