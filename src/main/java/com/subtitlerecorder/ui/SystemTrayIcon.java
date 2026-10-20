package com.subtitlerecorder.ui;

import com.subtitlerecorder.MainApp;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;

/**
 * 系统托盘图标
 */
public class SystemTrayIcon {

    private SystemTray tray = SystemTray.getSystemTray();

    // 创建托盘图标（使用默认系统图标）
    private Image image = Toolkit.getDefaultToolkit().createImage(
            getClass().getResource("/icon.png") != null
                    ? getClass().getResource("/icon.png")
                    : Toolkit.getDefaultToolkit().getClass().getResource("/java/awt/TrayIcon.class"));

    // 托盘变量
    private TrayIcon trayIcon = new TrayIcon(image,"RecOns屏幕录制");

    public void install() {
        try {

            PopupMenu popup = new PopupMenu();

            MenuItem showItem = new MenuItem("显示字幕窗口");
            showItem.addActionListener(e -> {
                javafx.application.Platform.runLater(() -> {
                    MainApp.subtitleOverlay.show();
                });
            });

            MenuItem startItem = new MenuItem("开始/停止录制 (F9)");
            startItem.addActionListener(e -> {
                javafx.application.Platform.runLater(() -> {
                    if (MainApp.recordingManager.isRecording()) {
                        MainApp.recordingManager.stopRecording();
                    } else {
                        if (MainApp.recordingManager.isModelExists()) {
                            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                            Rectangle bounds = ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                            MainApp.recordingManager.startRecording(bounds.x, bounds.y, bounds.width, bounds.height);
                        }
                    }
                });
            });

            MenuItem openDirItem = new MenuItem("打开录制目录");
            openDirItem.addActionListener(e -> {
                try {
                    File dir = new File("recordings");
                    if (!dir.exists())
                        dir.mkdirs();
                    Desktop.getDesktop().open(dir);
                } catch (Exception ignored) {
                }
            });

            MenuItem exitItem = new MenuItem("退出");
            exitItem.addActionListener(e -> {
                if (MainApp.recordingManager.isRecording()) {
                    MainApp.recordingManager.stopRecording();
                }
                System.exit(0);
            });

            popup.add(showItem);
            popup.add(startItem);
            popup.addSeparator();
            popup.add(openDirItem);
            popup.addSeparator();
            popup.add(exitItem);

            // trayIcon = new TrayIcon(image, "RecOns屏幕录制", popup);
            trayIcon.setPopupMenu(popup);
            trayIcon.setImageAutoSize(true);

            // 双击托盘图标显示窗口
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        javafx.application.Platform.runLater(() -> MainApp.subtitleOverlay.show());
                    }
                }
            });

            tray.add(trayIcon);
        } catch (Exception e) {
            // 系统托盘不可用时静默失败，不影响功能
            e.printStackTrace();
        }
    }

    public void showStatusMessage(String displayMsg) {
        // 显示消息
        try {
            TrayIcon[] trayIcons = tray.getTrayIcons();
            for (TrayIcon trayIcon : trayIcons) {
                trayIcon.displayMessage("RecOns屏幕录制", displayMsg, TrayIcon.MessageType.INFO);
            }
        } catch (Exception err) {
            err.printStackTrace();
        }
    }
}