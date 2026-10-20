package com.subtitlerecorder;

import com.subtitlerecorder.core.RecordingManager;
import com.subtitlerecorder.ui.SubtitleOverlay;
import com.subtitlerecorder.ui.SystemTrayIcon;
import com.subtitlerecorder.util.GlobalKeyListener;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;

/**
 * JavaFX语音字幕录屏软件 v0.3.1
 * 纯悬浮字幕界面模式，无主窗口
 * 基于JavaFX + JavaCV/FFmpeg + Vosk本地离线语音识别
 * 全局快捷键：F9=开始/停止录制，F10=暂停/恢复
 */
public class MainApp extends Application {
    private static final Logger logger = LoggerFactory.getLogger(MainApp.class);

    public static SubtitleOverlay subtitleOverlay;
    public static RecordingManager recordingManager;
    public static GlobalKeyListener keyListener;

    @Override
    public void start(Stage primaryStage) throws Exception {
        logger.info("RecOns 屏幕录制 v0.3.1 (纯悬浮字幕模式)...");

        // 创建输出目录
        // boolean recordings = new File("./recordings").mkdirs();
        // boolean models = new File("./models").mkdirs();

        // 初始化录制管理器
        recordingManager = new RecordingManager();

        // 初始化悬浮字幕窗口
        subtitleOverlay = new SubtitleOverlay();
        subtitleOverlay.show();

        // 初始化系统托盘图标（如果支持）
        if (SystemTray.isSupported()) {
            new SystemTrayIcon().install();
        }

        // 注册全局快捷键监听器
        keyListener = new GlobalKeyListener();
        keyListener.start();

        // 应用关闭时清理资源
        Platform.setImplicitExit(false);

        logger.info("软件启动成功，悬浮字幕窗口已显示");
        logger.info("快捷键：F9=开始/停止录制，F10=暂停/恢复");

        // 检查模型是否存在
        if (!recordingManager.isModelExists()) {
            subtitleOverlay.showStatusMessage("⚠️ 请下载Vosk语音模型",
                    "右键悬浮窗 → 模型设置 → 查看下载说明", 8000);
        }
    }

    public static void main(String[] args) {
        // 1. 【关键】设置 JNA 编码为 UTF-8，防止中文乱码
        System.setProperty("jna.encoding", "UTF-8");
        launch(args);
    }

    @Override
    public void stop() throws Exception {
        if (keyListener != null) {
            keyListener.stop();
        }
        if (recordingManager != null) {
            recordingManager.shutdown();
        }
        super.stop();
    }
}