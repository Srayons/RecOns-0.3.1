package com.subtitlerecorder.ui;

import java.awt.Desktop;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;

import com.subtitlerecorder.MainApp;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * 悬浮字幕窗口 - 录制时显示在屏幕底部
 */
public class SubtitleOverlay {
    private Stage stage;
    private Label subtitleLabel;
    private Label statusLabel;
    private Label timeLabel;
    private StackPane root;
    private FadeTransition fadeTransition;
    private ContextMenu contextMenu;
    private HBox statusBarNode;

    private double dragOffsetX;
    private double dragOffsetY;

    // 设置项
    private int fontSize = 28;
    private double opacity = 0.85;
    private boolean showHistory = true;

    // 状态
    private boolean isRecording = false;
    private boolean isPaused = false;
    private String latestFinalText = "";
    private String currentPartialText = "";

    private static final String FONT_FAMILY = "Microsoft YaHei, PingFang SC, Noto Sans CJK SC, sans-serif";
    private static final int DEFAULT_WIDTH = 1000;
    // private static final int MIN_WIDTH = 400;

    public SubtitleOverlay() {
        createOverlay();
        createContextMenu();
    }

    private void createOverlay() {
        stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        // 设置置顶
        stage.setAlwaysOnTop(true);

        root = new StackPane();
        root.setStyle("-fx-background-color: rgba(0, 0, 0, " + opacity + "); " +
                "-fx-background-radius: 10; " +
                "-fx-padding: 15 30 15 30;");

        // 字幕标签
        subtitleLabel = new Label("右键点击本窗口打开菜单\n快捷键：F9 开始/停止，F10 暂停/恢复");
        subtitleLabel.setFont(Font.font(FONT_FAMILY, FontWeight.NORMAL, fontSize));
        subtitleLabel.setTextFill(Color.WHITE);
        subtitleLabel.setWrapText(true);
        subtitleLabel.setMaxWidth(DEFAULT_WIDTH - 60);
        subtitleLabel.setAlignment(Pos.CENTER);
        subtitleLabel.setLineSpacing(5);

        // 顶部状态条容器
        HBox statusBar = new HBox(15);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(0, 0, 8, 0));

        statusLabel = new Label("● 就绪");
        statusLabel.setFont(Font.font(FONT_FAMILY, FontWeight.BOLD, 14));
        statusLabel.setTextFill(Color.LIGHTGREEN);

        timeLabel = new Label("00:00");
        timeLabel.setFont(Font.font(FONT_FAMILY, FontWeight.NORMAL, 14));
        timeLabel.setTextFill(Color.LIGHTGRAY);

        statusBar.getChildren().addAll(statusLabel, timeLabel);
        this.statusBarNode = statusBar; // 保存引用
        statusBar.setVisible(false); // 默认隐藏，录制时显示

        // 布局：StackPane里放垂直布局（这里简化，把状态栏放顶部，字幕放中间）
        javafx.scene.layout.VBox container = new javafx.scene.layout.VBox(5);
        container.setAlignment(Pos.BOTTOM_CENTER);
        container.getChildren().addAll(statusBar, subtitleLabel);

        root.getChildren().add(container);
        StackPane.setAlignment(container, Pos.CENTER);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);

        stage.setScene(scene);

        // 定位到屏幕底部中央
        positionAtBottom();

        // 淡入效果
        fadeTransition = new FadeTransition(Duration.millis(300), root);
        fadeTransition.setFromValue(0.0);
        fadeTransition.setToValue(1.0);

        // 鼠标拖拽移动窗口
        root.setOnMousePressed(this::handleMousePressed);
        root.setOnMouseDragged(this::handleMouseDragged);

        // 右键菜单
        root.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                contextMenu.show(stage.getOwner() == null ? stage : stage.getOwner(),
                        e.getScreenX(), e.getScreenY());
            } else if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                // 双击快速开始/停止录制
                toggleRecording();
            }
        });
    }

    private void createContextMenu() {
        contextMenu = new ContextMenu();
        contextMenu.setStyle("-fx-font-size: 14px; -fx-font-family: '" + FONT_FAMILY + "';");

        // 开始/停止录制
        MenuItem startStopItem = new MenuItem("● 开始录制 (F9)");
        startStopItem.setOnAction(e -> toggleRecording());

        // 暂停/恢复
        MenuItem pauseResumeItem = new MenuItem("⏸ 暂停录制 (F10)");
        pauseResumeItem.setOnAction(e -> togglePause());

        // 分隔符
        contextMenu.getItems().addAll(startStopItem, pauseResumeItem, new SeparatorMenuItem());

        // 全屏录制
        MenuItem fullScreenItem = new MenuItem("□ 全屏录制");
        fullScreenItem.setOnAction(e -> {
            RecordingSettings settings = RecordingSettings.getInstance();
            settings.setFullScreen(true);
            showStatusMessage("已设置为全屏录制", "", 2000);
        });

        // 选择区域录制
        MenuItem selectAreaItem = new MenuItem("▢ 选择录制区域");
        selectAreaItem.setOnAction(e -> selectRecordingArea());

        contextMenu.getItems().addAll(fullScreenItem, selectAreaItem, new SeparatorMenuItem());

        // 字体大小子菜单
        Menu fontSizeMenu = new Menu("🔤 字体大小");
        ToggleGroup fontSizeGroup = new ToggleGroup();
        for (int size : new int[] { 20, 24, 28, 32, 36, 42 }) {
            RadioMenuItem item = new RadioMenuItem(size + "px");
            item.setToggleGroup(fontSizeGroup);
            item.setSelected(size == fontSize);
            final int s = size;
            item.setOnAction(ev -> setFontSize(s));
            fontSizeMenu.getItems().add(item);
        }

        // 透明度子菜单
        Menu opacityMenu = new Menu("👁 窗口透明度");
        ToggleGroup opacityGroup = new ToggleGroup();
        for (double op : new double[] { 0.4, 0.6, 0.7, 0.85, 1.0 }) {
            RadioMenuItem item = new RadioMenuItem((int) (op * 100) + "%");
            item.setToggleGroup(opacityGroup);
            item.setSelected(Math.abs(op - opacity) < 0.01);
            final double o = op;
            item.setOnAction(ev -> setOpacityLevel(o));
            opacityMenu.getItems().add(item);
        }

        // 显示历史字幕选项
        // CheckMenuItem historyItem = new CheckMenuItem("显示上一句字幕");
        // historyItem.setSelected(showHistory);
        // historyItem.setOnAction(e -> showHistory = historyItem.isSelected());

        contextMenu.getItems().addAll(fontSizeMenu, opacityMenu);

        // 打开输出目录
        MenuItem openDirItem = new MenuItem("📂 打开录制文件目录");
        openDirItem.setOnAction(e -> openOutputDirectory());

        // 模型设置
        MenuItem modelItem = new MenuItem("⚙ 语音模型设置");
        modelItem.setOnAction(e -> showModelSettings());

        // 关闭字幕窗口
        MenuItem closeItem = new MenuItem("⚙ 关闭字幕窗口");
        closeItem.setOnAction(e -> hide());

        // 关于
        MenuItem aboutItem = new MenuItem("ℹ 关于");
        aboutItem.setOnAction(e -> showAbout());

        // 退出
        MenuItem exitItem = new MenuItem("✕ 退出软件");
        exitItem.setOnAction(e -> {
            if (MainApp.recordingManager.isRecording()) {
                MainApp.recordingManager.stopRecording();
            }
            Platform.exit();
            System.exit(0);
        });

        contextMenu.getItems().addAll(openDirItem, modelItem, new SeparatorMenuItem(), closeItem, aboutItem, exitItem);

        // 菜单显示时更新状态
        contextMenu.setOnShowing(e -> {
            if (isRecording) {
                startStopItem.setText("■ 停止录制 (F9)");
                pauseResumeItem.setDisable(false);
                pauseResumeItem.setText(isPaused ? "▶ 恢复录制 (F10)" : "⏸ 暂停录制 (F10)");
                fullScreenItem.setDisable(true);
                selectAreaItem.setDisable(true);
            } else {
                startStopItem.setText("● 开始录制 (F9)");
                pauseResumeItem.setDisable(true);
                pauseResumeItem.setText("⏸ 暂停录制 (F10)");
                fullScreenItem.setDisable(false);
                selectAreaItem.setDisable(false);
            }
        });
    }

    private void toggleRecording() {
        if (!MainApp.recordingManager.isModelExists()) {
            showModelSettings();
            return;
        }

        if (MainApp.recordingManager.isRecording()) {
            MainApp.recordingManager.stopRecording();
        } else {
            // 使用默认全屏开始录制
            RecordingSettings settings = RecordingSettings.getInstance();
            if (settings.isFullScreen()) {
                GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                GraphicsDevice gd = ge.getDefaultScreenDevice();
                Rectangle bounds = gd.getDefaultConfiguration().getBounds();
                MainApp.recordingManager.startRecording(bounds.x, bounds.y, bounds.width, bounds.height);
            } else {
                MainApp.recordingManager.startRecording(
                        settings.getCaptureX(), settings.getCaptureY(),
                        settings.getCaptureWidth(), settings.getCaptureHeight());
            }
            // 显示消息
            new SystemTrayIcon().showStatusMessage("开始录制（F9）");
        }
    }

    private void togglePause() {
        if (!isRecording)
            return;
        if (isPaused) {
            MainApp.recordingManager.resumeRecording();
        } else {
            MainApp.recordingManager.pauseRecording();
        }
    }

    private void selectRecordingArea() {
        // 隐藏悬浮窗，启动区域选择器
        stage.hide();
        Platform.runLater(() -> {
            AreaSelector selector = new AreaSelector(rect -> {
                Platform.runLater(() -> {
                    stage.show();
                    positionAtBottom();
                    if (rect != null) {
                        RecordingSettings settings = RecordingSettings.getInstance();
                        settings.setFullScreen(false);
                        settings.setCaptureArea(rect.x, rect.y, rect.width, rect.height);
                        showStatusMessage("已选择录制区域: " + rect.width + "x" + rect.height, "", 3000);
                    }
                });
            });
            selector.start();
        });
    }

    private void openOutputDirectory() {
        try {
            File dir = new File("recordings");
            if (!dir.exists())
                dir.mkdirs();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (IOException ex) {
            showStatusMessage("无法打开目录", ex.getMessage(), 3000);
        }
    }

    private void showModelSettings() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("语音模型设置");
        alert.setHeaderText("Vosk语音识别模型");
        alert.setContentText(
                "本软件使用Vosk开源语音识别引擎，无需联网。\n\n" +
                        "请将中文模型解压到 models 目录下：\n\n" +
                        "【推荐 - 识别率高】vosk-model-cn-0.22 (1.3GB)\n" +
                        "【轻量 - 速度快】 vosk-model-small-cn-0.22 (40MB)\n\n" +
                        "下载地址：https://alphacephei.com/vosk/models\n\n" +
                        "当前模型状态：" + (MainApp.recordingManager.isModelExists() ? "✅ 已加载" : "❌ 未找到") + "\n" +
                        "模型路径：" + new File("models").getAbsolutePath());
        alert.initOwner(stage);
        alert.show();
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("关于");
        alert.setHeaderText("RecOns 屏幕录制 v0.3.1");
        alert.setContentText(
                "纯悬浮字幕界面模式\n\n" +
                        "✅ 免费开源\n" +
                        "✅ 离线识别，保护隐私\n" +
                        "✅ 实时字幕显示\n" +
                        "✅ 自动生成SRT字幕文件\n" +
                        "✅ MP4高清录屏\n\n" +
                        "快捷键：\n" +
                        "F9  - 开始/停止录制\n" +
                        "F10 - 暂停/恢复\n" +
                        "双击字幕 - 快速开始/停止\n" +
                        "右键字幕 - 打开菜单\n" +
                        "拖拽字幕 - 移动位置\n" +
                        "JumpOns团队制作（ai）");
        alert.initOwner(stage);
        alert.show();
    }

    private void handleMousePressed(MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY) {
            dragOffsetX = stage.getX() - e.getScreenX();
            dragOffsetY = stage.getY() - e.getScreenY();
        }
    }

    private void handleMouseDragged(MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY) {
            stage.setX(e.getScreenX() + dragOffsetX);
            stage.setY(e.getScreenY() + dragOffsetY);
        }
    }

    private void positionAtBottom() {
        Screen screen = Screen.getPrimary();
        double screenWidth = screen.getVisualBounds().getWidth();
        double screenHeight = screen.getVisualBounds().getHeight();
        stage.setX((screenWidth - DEFAULT_WIDTH) / 2);
        stage.setY(screenHeight - 262);
        stage.setWidth(DEFAULT_WIDTH);
    }

    public void setFontSize(int size) {
        this.fontSize = size;
        subtitleLabel.setFont(Font.font(FONT_FAMILY, FontWeight.NORMAL, size));
        updateSubtitleDisplay();
        stage.sizeToScene();
    }

    public void setOpacityLevel(double op) {
        this.opacity = op;
        root.setStyle("-fx-background-color: rgba(0, 0, 0, " + op + "); " +
                "-fx-background-radius: 10; " +
                "-fx-padding: 15 30 15 30;");
    }

    public void show() {
        Platform.runLater(() -> {
            stage.show();
            positionAtBottom();
            fadeTransition.playFromStart();
        });
    }

    public void hide() {
        Platform.runLater(() -> {
            stage.hide();
            clearSubtitle();
        });
    }

    public void updateRecordingStatus(boolean recording, boolean paused) {
        this.isRecording = recording;
        this.isPaused = paused;
        Platform.runLater(() -> {
            if (recording) {
                statusBarVisible(true);
                if (paused) {
                    statusLabel.setText("⏸ 已暂停");
                    statusLabel.setTextFill(Color.YELLOW);
                } else {
                    statusLabel.setText("● 录制中");
                    statusLabel.setTextFill(Color.RED);
                }
            } else {
                statusBarVisible(false);
                statusLabel.setText("● 就绪");
                statusLabel.setTextFill(Color.LIGHTGREEN);
            }
        });
    }

    private void statusBarVisible(boolean visible) {
        if (statusBarNode != null) {
            statusBarNode.setVisible(visible);
        }
    }

    public void updateTime(long timeMs) {
        Platform.runLater(() -> {
            long totalSec = timeMs / 1000;
            long min = totalSec / 60;
            long sec = totalSec % 60;
            timeLabel.setText(String.format("%02d:%02d", min, sec));
        });
    }

    public void showFinalText(String text) {
        this.latestFinalText = text;
        updateSubtitleDisplay();
    }

    public void showPartialText(String text) {
        this.currentPartialText = text;
        updateSubtitleDisplay();
    }

    public void clearSubtitle() {
        this.latestFinalText = "";
        this.currentPartialText = "";
        Platform.runLater(() -> {
            subtitleLabel.setText("");
        });
    }

    private void updateSubtitleDisplay() {
        Platform.runLater(() -> {
            StringBuilder display = new StringBuilder();
            if (showHistory && !latestFinalText.isEmpty()) {
                display.append(latestFinalText).append("\n");
            }
            if (!currentPartialText.isEmpty()) {
                display.append(currentPartialText);
            }
            String text = display.toString().trim();
            // System.out.println("字幕文字：" + text);
            // System.out.println("subtitleLabel：" + subtitleLabel.getAlignment());
            if (text.isEmpty()) {
                if (isRecording) {
                    updateSubtitle(text);
                    // subtitleLabel.setText("...");
                }
            } else {
                updateSubtitle(text);
                // subtitleLabel.setText(text);
            }
            stage.sizeToScene();
        });
    }

    /**
     * 显示状态提示消息
     */
    public void showStatusMessage(String title, String message, long durationMs) {
        Platform.runLater(() -> {
            StringBuilder sb = new StringBuilder(title);
            if (message != null && !message.isEmpty()) {
                sb.append("\n").append(message);
            }
            subtitleLabel.setText(sb.toString());
            stage.sizeToScene();
            positionAtBottom();

            // durationMs后自动恢复
            if (durationMs > 0) {
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(durationMs);
                        if (!isRecording) {
                            Platform.runLater(() -> subtitleLabel.setText(
                                    "右键点击本窗口打开菜单\n快捷键：F9 开始/停止，F10 暂停/恢复"));
                        }
                    } catch (InterruptedException ignored) {
                    }
                });
                t.setDaemon(true);
                t.start();
            }
        });
    }

    public void recordingComplete(String videoPath, String srtPath) {
        Platform.runLater(() -> {
            isRecording = false;
            isPaused = false;
            updateRecordingStatus(false, false);
            clearSubtitle();
            showStatusMessage("✅ 录制完成",
                    "视频：" + new File(videoPath).getName() + "\n字幕：" + new File(srtPath).getName(),
                    5000);
        });
    }

    /**
     * 更新字幕时间
     * 
     * @param currentTime
     */
    public void updateSubtitle(long currentTime) {
        Platform.runLater(() -> {
            long totalSec = currentTime / 1000;
            long min = totalSec / 60;
            long sec = totalSec % 60;
            timeLabel.setText(String.format("%02d:%02d", min, sec));
        });
    }

    /**
     * 更新字幕居中
     * 
     * @param text
     */
    public void updateSubtitle(String text) {
        Platform.runLater(() -> {
            if (text == null || text.trim().isEmpty()) {
                return;
            }
            subtitleLabel.setText(text);
            stage.sizeToScene();
            positionAtBottom();
        });
    }

    // public void updateSubtitle(long currentTime) {
    // // TODO Auto-generated method stub
    // throw new UnsupportedOperationException("Unimplemented method
    // 'updateSubtitle'");
    // }

    // public void updateRecordingStatus(boolean b, boolean c) {
    // // TODO Auto-generated method stub
    // throw new UnsupportedOperationException("Unimplemented method
    // 'updateRecordingStatus'");
    // }

    // public void showStatusMessage(String string, String string2, int i) {
    // // TODO Auto-generated method stub
    // throw new UnsupportedOperationException("Unimplemented method
    // 'showStatusMessage'");
    // }

    // public void clearSubtitle() {
    // // TODO Auto-generated method stub
    // throw new UnsupportedOperationException("Unimplemented method
    // 'clearSubtitle'");
    // }

    // public void showFinalText(String finalText) {
    // // TODO Auto-generated method stub
    // throw new UnsupportedOperationException("Unimplemented method
    // 'showFinalText'");
    // }

    // public void recordingComplete(String outputVideoPath, String outputSrtPath) {
    // // TODO Auto-generated method stub
    // throw new UnsupportedOperationException("Unimplemented method
    // 'recordingComplete'");
    // }
}