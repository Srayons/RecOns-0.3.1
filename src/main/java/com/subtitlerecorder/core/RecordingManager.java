package com.subtitlerecorder.core;

import com.subtitlerecorder.MainApp;
import com.subtitlerecorder.asr.AudioDispatcher;
import com.subtitlerecorder.asr.VoskASR;
import com.subtitlerecorder.ui.SystemTrayIcon;

import javafx.application.Platform;

import javax.sound.sampled.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 录制管理器 - 负责协调录屏、录音、语音识别的工作流程
 * v0.3.1 适配纯悬浮字幕界面
 */
public class RecordingManager {

    private static final Logger logger = LoggerFactory.getLogger(RecordingManager.class);

    private ScreenRecorder screenRecorder;
    private VoskASR voskASR;
    private AudioDispatcher audioDispatcher;
    private volatile boolean isRecording = false;
    private volatile boolean isPaused = false;
    private long recordingStartTime;
    private long pausedDuration = 0;
    private long lastPauseTime = 0;

    private TargetDataLine audioLine;

    private String outputVideoPath;
    private String outputSrtPath;

    private List<SubtitleItem> subtitles = new ArrayList<>();

    // 字幕分段逻辑
    private StringBuilder currentSentence = new StringBuilder();
    private long sentenceStartTime = 0;
    private static final int MAX_SENTENCE_LENGTH = 34; // 单句最大字数，避免太长
    private static final long MAX_SENTENCE_DURATION_MS = 8000; // 单句最长8秒，避免超时
    private static final long SILENCE_THRESHOLD_MS = 1500; // 静音1.5秒以上断句

    private Thread timeUpdateThread;
    private long lastFinalResultTime = 0;
    // private Consumer<String> onSubtitleUpdate;
    // private Consumer<Long> onTimeUpdate;
    // private Consumer<String> onStatusUpdate;
    // private Consumer<String[]> onRecordingComplete;
    // private Consumer<Throwable> onError;

    public RecordingManager() {
        voskASR = new VoskASR();
        // 提前初始化ASR模型，避免开始录制时加载卡顿
        new Thread(() -> {
            try {
                if (voskASR.isModelExists()) {
                    voskASR.init();
                    setupASRCallbacks();
                    MainApp.subtitleOverlay.showStatusMessage("✅ 语音模型加载完成", "按F9开始录制，双击字幕快速开始", 3000);
                }
            } catch (Exception e) {
                MainApp.subtitleOverlay.showStatusMessage("❌ 语音模型加载失败", "请检查文件完整性或网络连接", 3000);
                e.printStackTrace();
            }
        }, "ASR-Init-Thread").start();
    }

    /**
     * 检查模型是否存在
     */
    public boolean isModelExists() {
        return voskASR.isModelExists();
    }

    /**
     * 配置ASR回调
     */
    public void setupASRCallbacks() {
        // 实时临时结果（识别中...）
        voskASR.setOnPartialResult(text -> {
            if (!isRecording || isPaused || text.isEmpty())
                return;
            Platform.runLater(() -> MainApp.subtitleOverlay.showFinalText(text));
        });

        // 最终确认结果（一句话说完）
        voskASR.setOnFinalResult(text -> {
            if (!isRecording || isPaused || text.isEmpty())
                return;

            Platform.runLater(() -> {
                long currentTime = getCurrentRecordingTime();
                lastFinalResultTime = System.currentTimeMillis();

                currentSentence.append(text);

                // 判断是否需要分段（句号/问号/感叹号结尾，或达到最大长度，或超过最长时长）
                boolean shouldSplit = currentSentence.length() >= MAX_SENTENCE_LENGTH ||
                        (currentTime - sentenceStartTime) >= MAX_SENTENCE_DURATION_MS ||
                        text.endsWith("。") || text.endsWith("！") || text.endsWith("？") ||
                        text.endsWith(".") || text.endsWith("!") || text.endsWith("?");

                if (shouldSplit && currentSentence.length() > 1) {
                    // 保存一句字幕
                    SubtitleItem item = new SubtitleItem();
                    String finalText = currentSentence.toString().trim();
                    // 自动加标点（如果没有）
                    if (!finalText.endsWith("。") && !finalText.endsWith("？") &&
                            !finalText.endsWith("！") && !finalText.endsWith(".") &&
                            !finalText.endsWith("?") && !finalText.endsWith("!")) {
                        finalText += "。";
                    }
                    item.setText(finalText);
                    item.setStartTime(sentenceStartTime);
                    item.setEndTime(currentTime);
                    subtitles.add(item);

                    // 更新悬浮窗显示最终结果
                    MainApp.subtitleOverlay.showFinalText(finalText);

                    // 重置句子缓冲
                    currentSentence.setLength(0);
                    sentenceStartTime = currentTime;
                }
            });
        });

        voskASR.setOnError(e -> {
            Platform.runLater(() -> {
                MainApp.subtitleOverlay.showStatusMessage("❌ 识别错误", e.getMessage(), 5000);
            });
        });
    }

    /**
     * 初始化音频设备 - 优化音频输入质量
     */
    private boolean initAudio() {
        try {
            // Vosk要求的音频格式：16kHz, 16bit, 单声道, PCM有符号, 小端序
            AudioFormat format = VoskASR.VOSK_AUDIO_FORMAT;
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                // 尝试查找所有可用麦克风线路
                Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
                for (Mixer.Info mixerInfo : mixerInfos) {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    Line.Info[] lineInfos = mixer.getTargetLineInfo();
                    for (Line.Info lineInfo : lineInfos) {
                        if (lineInfo.getLineClass().equals(TargetDataLine.class)) {
                            try {
                                audioLine = (TargetDataLine) mixer.getLine(lineInfo);
                                audioLine.open(format, 16000); // 16KB缓冲区，减少延迟
                                logger.info("初始化音频完成");
                                return true;
                            } catch (Exception ignored) {
                                System.err.println("音频出错：" + ignored.getMessage());
                                ignored.printStackTrace();
                            }
                        }
                    }
                }
                throw new LineUnavailableException("未找到可用的麦克风设备，请检查麦克风是否已连接");
            }

            audioLine = (TargetDataLine) AudioSystem.getLine(info);
            audioLine.open(format, 16000); // 增大缓冲区，减少爆音

            // 尝试优化麦克风增益（可选）
            try {
                if (audioLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gainControl = (FloatControl) audioLine.getControl(FloatControl.Type.MASTER_GAIN);
                    // 设置适中增益，避免过载爆音或声音太小
                    float maxGain = gainControl.getMaximum();
                    float minGain = gainControl.getMinimum();
                    gainControl.setValue(Math.min(maxGain - 3.0f, Math.max(minGain, 0.0f)));
                }
            } catch (Exception ignored) {
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> {
                MainApp.subtitleOverlay.showStatusMessage("❌ 麦克风初始化失败", e.getMessage(), 5000);
            });
            return false;
        }
    }

    /**
     * 开始录制
     */
    public boolean startRecording(int x, int y, int width, int height) {
        if (isRecording)
            return false;

        try {
            // 检查模型
            if (!voskASR.isModelExists()) {
                Platform.runLater(() -> {
                    MainApp.subtitleOverlay.showStatusMessage("⚠️ 请先下载语音模型",
                            "右键 → 语音模型设置 查看下载说明", 5000);
                });
                return false;
            }

            // 初始化音频
            if (!initAudio()) {
                return false;
            }

            logger.info("音频已打开，音频正常");

            // 创建输出目录
            File outputDir = new File("recordings");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            outputVideoPath = new File(outputDir, "recording_" + timestamp + ".mp4").getAbsolutePath();
            outputSrtPath = new File(outputDir, "recording_" + timestamp + ".srt").getAbsolutePath();

            // 创建音频分发器（先创建但不启动）
            audioDispatcher = new AudioDispatcher(audioLine);

            // 初始化屏幕录制器
            screenRecorder = new ScreenRecorder(x, y, width, height, outputVideoPath, audioDispatcher);

            // 开始语音识别（注册 Vosk 消费者）
            voskASR.startRecognition(audioDispatcher, audioLine);

            // 开始录音，然后启动分发器（确保消费者已注册）
            audioLine.start();

            if (!screenRecorder.start()) {
                throw new Exception("无法启动屏幕录制，请检查屏幕权限");
            }
            audioDispatcher.start();

            // 如果ASR还没初始化则先初始化
            if (!voskASR.init()) {
                throw new Exception("语音识别模型初始化失败");
            }
            setupASRCallbacks();

            // 重置字幕数据
            subtitles.clear();
            currentSentence.setLength(0);
            sentenceStartTime = 0;
            lastFinalResultTime = System.currentTimeMillis();

            recordingStartTime = System.currentTimeMillis();
            pausedDuration = 0;
            isPaused = false;
            isRecording = true;

            // 更新UI状态
            MainApp.subtitleOverlay.updateRecordingStatus(true, false);
            MainApp.subtitleOverlay.clearSubtitle();
            MainApp.subtitleOverlay.showStatusMessage("● 录制开始", "", 1500);

            // 启动时间更新线程
            startTimeUpdateThread();

            return true;

        } catch (Exception e) {
            e.printStackTrace();
            cleanup();
            Platform.runLater(() -> {
                MainApp.subtitleOverlay.updateRecordingStatus(false, false);
                MainApp.subtitleOverlay.showStatusMessage("❌ 录制启动失败", e.getMessage(), 5000);
            });
            return false;
        }
    }

    /**
     * 暂停录制
     */
    public void pauseRecording() {
        if (!isRecording || isPaused)
            return;

        isPaused = true;
        lastPauseTime = System.currentTimeMillis();

        if (screenRecorder != null) {
            screenRecorder.pause();
        }

        MainApp.subtitleOverlay.updateRecordingStatus(true, true);
        MainApp.subtitleOverlay.showStatusMessage("⏸ 已暂停", "", 1000);
    }

    /**
     * 恢复录制
     */
    public void resumeRecording() {
        if (!isRecording || !isPaused)
            return;

        pausedDuration += System.currentTimeMillis() - lastPauseTime;
        isPaused = false;

        if (screenRecorder != null) {
            screenRecorder.resume();
        }

        MainApp.subtitleOverlay.updateRecordingStatus(true, false);
        MainApp.subtitleOverlay.showStatusMessage("● 录制恢复", "", 1000);
    }

    /**
     * 停止录制
     */
    public void stopRecording() {
        if (!isRecording)
            return;

        isRecording = false;

        try {
            // 停止语音识别
            voskASR.stopRecognition();

            // 停止录制
            if (screenRecorder != null) {
                screenRecorder.stop();
                screenRecorder = null;
            }

            // 停止音频分发
            if (audioDispatcher != null) {
                audioDispatcher.stop();
                audioDispatcher = null;
            }

            if (audioLine != null) {
                audioLine.stop();
                audioLine.close();
                audioLine = null;
            }

            // 处理最后剩余的字幕
            if (currentSentence.length() > 0) {
                SubtitleItem item = new SubtitleItem();
                String text = currentSentence.toString().trim();
                if (!text.endsWith("。") && !text.endsWith("？") && !text.endsWith("！")) {
                    text += "。";
                }
                item.setText(text);
                item.setStartTime(sentenceStartTime);
                item.setEndTime(getCurrentRecordingTime());
                subtitles.add(item);
                MainApp.subtitleOverlay.showFinalText(text);
            }

            // 导出SRT字幕文件
            exportSrtFile();

            // 更新UI
            MainApp.subtitleOverlay.updateRecordingStatus(false, false);
            MainApp.subtitleOverlay.recordingComplete(outputVideoPath, outputSrtPath);

            // 显示消息
            new SystemTrayIcon().showStatusMessage("保存文件：" + outputVideoPath + "\n" +
                    "字幕文件：" + outputSrtPath);
        } catch (Exception e) {
            e.printStackTrace();
            MainApp.subtitleOverlay.showStatusMessage("❌ 停止录制出错", e.getMessage(), 3000);
        }
    }

    /**
     * 程序退出时清理资源
     */
    public void shutdown() {
        if (isRecording) {
            stopRecording();
        }
        voskASR.close();
    }

    /**
     * 导出SRT字幕文件
     */
    private void exportSrtFile() {
        try {
            if (subtitles.isEmpty())
                return;
            SrtWriter.write(subtitles, outputSrtPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 启动时间更新线程
     */
    private void startTimeUpdateThread() {
        timeUpdateThread = new Thread(() -> {
            while (isRecording) {
                if (!isPaused) {
                    long currentTime = getCurrentRecordingTime();
                    Platform.runLater(() -> {
                        MainApp.subtitleOverlay.updateSubtitle(currentTime);

                        // 静音超过阈值自动断句
                        if (currentSentence.length() > 0 &&
                                System.currentTimeMillis() - lastFinalResultTime > SILENCE_THRESHOLD_MS) {
                            // 强制断句
                            SubtitleItem item = new SubtitleItem();
                            String text = currentSentence.toString().trim();
                            if (!text.endsWith("。"))
                                text += "。";
                            item.setText(text);
                            item.setStartTime(sentenceStartTime);
                            item.setEndTime(currentTime);
                            subtitles.add(item);
                            MainApp.subtitleOverlay.showFinalText(text);
                            currentSentence.setLength(0);
                            sentenceStartTime = currentTime;
                        }
                    });
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "Time-Update-Thread");
        timeUpdateThread.setDaemon(true);
        timeUpdateThread.start();
    }

    /**
     * 获取当前录制时长（毫秒）
     */
    public long getCurrentRecordingTime() {
        if (!isRecording)
            return 0;
        long currentPausedTime = isPaused ? (System.currentTimeMillis() - lastPauseTime) : 0;
        return System.currentTimeMillis() - recordingStartTime - pausedDuration - currentPausedTime;
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        if (audioLine != null) {
            try {
                audioLine.stop();
                audioLine.close();
            } catch (Exception ignored) {
            }
            audioLine = null;
        }
    }

    public boolean isRecording() {
        return isRecording;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public void setModelPath(String modelPath) {
        voskASR.setModelPath(modelPath);
    }

    public boolean initASR() {
        return voskASR.init();
    }

    // public void setOnStatusUpdate(Consumer<String> onStatusUpdate) {
    // this.onStatusUpdate = onStatusUpdate;
    // }

    // public void setOnSubtitleUpdate(Consumer<String> onSubtitleUpdate) {
    // this.onSubtitleUpdate = onSubtitleUpdate;
    // }

    // public void setOnTimeUpdate(Consumer<Long> onTimeUpdate) {
    // this.onTimeUpdate = onTimeUpdate;
    // }

    // public void setOnRecordingComplete(Consumer<String[]> onRecordingComplete) {
    // // 录制完成回调，传入视频路径和字幕路径
    // this.onRecordingComplete = onRecordingComplete;
    // }

    // public void setOnError(Consumer<Throwable> onError) {
    // this.onError = onError;
    // }
}