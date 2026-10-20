package com.subtitlerecorder.util;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.subtitlerecorder.MainApp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.logging.Level;

/**
 * 全局快捷键监听器
 * 使用JNativeHook实现系统级全局快捷键，无需窗口焦点即可响应
 * 
 * 快捷键：
 * F9  - 开始/停止录制
 * F10 - 暂停/恢复录制
 */
public class GlobalKeyListener implements NativeKeyListener {
    private static final Logger logger = LoggerFactory.getLogger(GlobalKeyListener.class);
    
    private boolean running = false;

    public void start() {
        try {
            // 关闭JNativeHook的日志输出
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
            logger.setLevel(Level.WARNING);
            logger.setUseParentHandlers(false);
            
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            running = true;
            logger.info("全局快捷键监听器已启动");
        } catch (NativeHookException e) {
            logger.error("无法注册全局快捷键，需要以管理员权限运行", e);
            MainApp.subtitleOverlay.showStatusMessage("⚠️ 快捷键不可用", 
                "请以管理员权限运行软件，或使用右键菜单操作", 5000);
        }
    }
    
    public void stop() {
        if (running) {
            try {
                GlobalScreen.removeNativeKeyListener(this);
                GlobalScreen.unregisterNativeHook();
            } catch (NativeHookException e) {
                logger.error("注销全局快捷键失败", e);
            }
            running = false;
        }
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int keyCode = e.getKeyCode();
        
        if (keyCode == NativeKeyEvent.VC_F9) {
            // F9: 开始/停止录制
            javafx.application.Platform.runLater(() -> {
                if (MainApp.recordingManager.isRecording()) {
                    MainApp.recordingManager.stopRecording();
                } else {
                    if (!MainApp.recordingManager.isModelExists()) {
                        MainApp.subtitleOverlay.showStatusMessage("⚠️ 请先下载语音模型", 
                            "右键悬浮窗 → 模型设置", 3000);
                        return;
                    }
                    // 默认全屏录制
                    java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
                    java.awt.Rectangle bounds = ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                    MainApp.recordingManager.startRecording(bounds.x, bounds.y, bounds.width, bounds.height);
                }
            });
        } else if (keyCode == NativeKeyEvent.VC_F10) {
            // F10: 暂停/恢复
            javafx.application.Platform.runLater(() -> {
                if (MainApp.recordingManager.isRecording()) {
                    if (MainApp.recordingManager.isPaused()) {
                        MainApp.recordingManager.resumeRecording();
                    } else {
                        MainApp.recordingManager.pauseRecording();
                    }
                }
            });
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        // 不需要处理
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        // 不需要处理
    }
}