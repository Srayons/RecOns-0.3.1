package com.subtitlerecorder.ui;

import com.subtitlerecorder.core.RecordingManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.awt.*;
import java.io.File;
import java.net.URL;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * 主界面控制器
 * 使用 Vosk 模型语音识别，完全免费
 */
public class MainController implements Initializable {

    @FXML
    private Button btnRecord;   //录制
    @FXML
    private Button btnPause;    //暂停
    @FXML
    private Button btnStop;     //停止
    @FXML
    private Button btnSelectArea;   //选择区域
    @FXML
    private Button btnSettings;   //设置
    @FXML
    private Button btnOpenFolder;   //文件目录

    @FXML
    private Label lblTime;      //时间
    @FXML
    private Label lblStatus;    //状态
    @FXML
    private Label lblSubtitlePreview;
    @FXML
    private CheckBox chkEnableASR;     //启用字幕识别
    @FXML
    private CheckBox chkFullScreen;    //全屏录制

    @FXML
    private VBox rootPane;

    private RecordingManager recordingManager;
    private SubtitleOverlay subtitleOverlay;
    private Rectangle selectedArea;
    private boolean isAreaSelected = false;
    private String modelPath = "models/vosk-model-small-cn-0.22";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        recordingManager = new RecordingManager();

        initControls();
        initBindings();
        initKeyboardShortcuts();
        // setupCallbacks();

        // 创建悬浮字幕窗口
        Platform.runLater(() -> {
            try {
                subtitleOverlay = new SubtitleOverlay();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // 初始化ASR引擎
        if (chkEnableASR.isSelected()) {
            recordingManager.setModelPath(modelPath);
            // recordingManager.setupASRCallbacks();
            boolean modelReady = recordingManager.initASR();
            if (modelReady) {
                updateStatus("就绪 - 语音模型已加载", Color.GREEN);
            } else {
                updateStatus("就绪 - 请先下载语音模型", Color.ORANGE);
            }
        } else {
            updateStatus("就绪", Color.BLACK);
        }
    }

    /**
     * 设置所有回调函数
     */
    // private void setupCallbacks() {
        // recordingManager.setOnStatusUpdate(status -> Platform.runLater(() -> {
        //     if (status.contains("完成")) {
        //         updateStatus(status, Color.GREEN);
        //     } else if (status.contains("错误") || status.contains("失败")) {
        //         updateStatus(status, Color.RED);
        //     } else {
        //         updateStatus(status, Color.web("#4CAF50"));
        //     }
        // }));

        // recordingManager.setOnSubtitleUpdate(text -> Platform.runLater(() -> {
        //     lblSubtitlePreview.setText(text);
        //     if (subtitleOverlay != null && chkEnableASR.isSelected()) {
        //         //更新字幕文字
        //         System.out.println("识别文字：" + text);
        //         subtitleOverlay.updateSubtitle(text);
        //     }
        // }));

        // recordingManager.setOnTimeUpdate(ms -> Platform.runLater(() -> {
        //     lblTime.setText(formatTime(ms));
        // }));

        // recordingManager.setOnError(e -> Platform.runLater(() -> {
        //     updateStatus("错误: " + e.getMessage(), Color.RED);
        //     showAlert(Alert.AlertType.ERROR, "错误", e.getMessage());

        //     // 恢复窗口
        //     Stage stage = (Stage) btnRecord.getScene().getWindow();
        //     stage.setIconified(false);
        //     stage.toFront();

        //     if (subtitleOverlay != null) {
        //         subtitleOverlay.hide();
        //     }
        // }));

        // recordingManager.setOnRecordingComplete(paths -> Platform.runLater(() -> {
        //     updateStatus("录制完成: " + new File(paths[0]).getName(), Color.GREEN);
        //     lblSubtitlePreview.setText("");

        //     if (subtitleOverlay != null) {
        //         subtitleOverlay.updateSubtitle("");
        //         subtitleOverlay.hide();
        //     }

        //     // 恢复窗口
        //     Stage stage = (Stage) btnRecord.getScene().getWindow();
        //     stage.setIconified(false);
        //     stage.toFront();

        //     updateButtonStates(false, false);

        //     showAlert(Alert.AlertType.INFORMATION, "录制完成",
        //             "视频文件:\n" + paths[0] + "\n\n字幕文件:\n" + paths[1]);
        // }));
    // }

    private void initControls() {
        chkEnableASR.setSelected(true);
        chkFullScreen.setSelected(true);

        chkEnableASR.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                recordingManager.setModelPath(modelPath);
                recordingManager.setupASRCallbacks();
                if (recordingManager.initASR()) {
                    updateStatus("就绪 - 语音识别已启用", Color.GREEN);
                }
            }
        });
    }

    private void initBindings() {
        btnRecord.setOnAction(e -> startRecording());
        btnPause.setOnAction(e -> togglePause());
        btnStop.setOnAction(e -> stopRecording());
        btnSelectArea.setOnAction(e -> selectCaptureArea());
        btnSettings.setOnAction(e -> showSettings());
        btnOpenFolder.setOnAction(e -> openOutputFolder());
    }

    private void initKeyboardShortcuts() {
        // F9 开始/停止录制
        rootPane.getScene().getAccelerators().put(
                new KeyCodeCombination(KeyCode.F9),
                this::toggleRecording);
        // F10 暂停/恢复
        rootPane.getScene().getAccelerators().put(
                new KeyCodeCombination(KeyCode.F10),
                this::togglePause);
    }

    private void toggleRecording() {
        if (recordingManager.isRecording()) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        try {
            // 检查模型（如果启用ASR）
            if (chkEnableASR.isSelected()) {
                File modelDir = new File(modelPath);
                if (!modelDir.exists()) {
                    showModelDownloadDialog();
                    return;
                }
            }

            // 确定录制区域
            int x, y, width, height;
            if (chkFullScreen.isSelected() || !isAreaSelected) {
                // 全屏
                GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                GraphicsDevice gd = ge.getDefaultScreenDevice();
                Rectangle bounds = gd.getDefaultConfiguration().getBounds();
                x = bounds.x;
                y = bounds.y;
                width = bounds.width;
                height = bounds.height;
            } else {
                x = selectedArea.x;
                y = selectedArea.y;
                width = selectedArea.width;
                height = selectedArea.height;
            }

            // 隐藏主窗口
            Stage stage = (Stage) btnRecord.getScene().getWindow();
            stage.setIconified(true);

            // 显示悬浮字幕
            if (subtitleOverlay != null && chkEnableASR.isSelected()) {
                subtitleOverlay.show();
            }

            // 开始录制
            boolean success = recordingManager.startRecording(x, y, width, height);
            if (success) {
                updateStatus("录制中...", Color.web("#4CAF50"));
                updateButtonStates(true, false);
            } else {
                stage.setIconified(false);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            showAlert(Alert.AlertType.ERROR, "录制失败", ex.getMessage());
            updateStatus("录制失败", Color.RED);

            Stage stage = (Stage) btnRecord.getScene().getWindow();
            stage.setIconified(false);
        }
    }

    private void togglePause() {
        if (recordingManager.isPaused()) {
            recordingManager.resumeRecording();
            btnPause.setText("暂停");
            updateStatus("录制中...", Color.web("#4CAF50"));
        } else if (recordingManager.isRecording()) {
            recordingManager.pauseRecording();
            btnPause.setText("继续");
            updateStatus("已暂停", Color.ORANGE);
        }
    }

    private void stopRecording() {
        recordingManager.stopRecording();
        btnPause.setText("暂停");
    }

    private void selectCaptureArea() {
        Stage stage = (Stage) btnSelectArea.getScene().getWindow();
        stage.setIconified(true);
        Platform.runLater(() -> {
            AreaSelector selector = new AreaSelector(rect -> {
                Platform.runLater(() -> {
                    if (rect != null && rect.width > 10 && rect.height > 10) {
                        selectedArea = rect;
                        isAreaSelected = true;
                        chkFullScreen.setSelected(false);
                        updateStatus(String.format("已选择区域: %dx%d+%d+%d", rect.width, rect.height, rect.x, rect.y),
                                Color.web("#2196F3"));
                    }
                    stage.setIconified(false);
                });
            });
            selector.start();
        });
    }

    /**
     * 显示设置对话框
     */
    private void showSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("设置");
        dialog.setHeaderText("语音识别设置 (Vosk本地离线引擎)");

        VBox content = new VBox(10);
        content.setPrefWidth(450);

        TextField tfModelPath = new TextField(modelPath);
        tfModelPath.setPromptText("模型文件夹路径");

        Button btnBrowse = new Button("浏览...");
        btnBrowse.setOnAction(e -> {
            DirectoryChooser dirChooser = new DirectoryChooser();
            dirChooser.setTitle("选择Vosk模型文件夹");
            File dir = dirChooser.showDialog(dialog.getOwner());
            if (dir != null) {
                tfModelPath.setText(dir.getAbsolutePath());
            }
        });

        javafx.scene.layout.HBox pathBox = new javafx.scene.layout.HBox(10, tfModelPath, btnBrowse);

        TextArea infoArea = new TextArea(
                "💡 Vosk是完全免费的开源离线语音识别引擎\n" +
                        "✅ 无需联网\n" +
                        "✅ 无需API密钥\n" +
                        "✅ 无调用次数限制\n\n" +
                        "📥 模型下载地址:\n" +
                        "https://alphacephei.com/vosk/models\n\n" +
                        "📦 推荐中文模型:\n" +
                        "• vosk-model-small-cn-0.22 (轻量版, ~40MB)\n" +
                        "• vosk-model-cn-0.22 (标准版, ~1.3GB, 更准确)\n\n" +
                        "📂 下载后解压到models文件夹即可");
        infoArea.setEditable(false);
        infoArea.setWrapText(true);
        infoArea.setPrefRowCount(12);

        content.getChildren().addAll(
                new Label("语音模型路径:"),
                pathBox,
                new Separator(),
                infoArea);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            modelPath = tfModelPath.getText();
            recordingManager.setModelPath(modelPath);
            if (chkEnableASR.isSelected()) {
                recordingManager.setupASRCallbacks();
                if (recordingManager.initASR()) {
                    updateStatus("设置已保存 - 模型加载成功", Color.GREEN);
                    showAlert(Alert.AlertType.INFORMATION, "成功", "设置已保存，模型加载成功！");
                } else {
                    updateStatus("设置已保存 - 模型路径无效", Color.ORANGE);
                }
            }
        }
    }

    /**
     * 显示模型下载提示
     */
    private void showModelDownloadDialog() {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("语音模型未找到");
        alert.setHeaderText("请先下载Vosk语音模型");
        alert.setContentText(
                "当前模型路径: " + modelPath + "\n\n" +
                        "下载步骤：\n" +
                        "1. 访问 https://alphacephei.com/vosk/models\n" +
                        "2. 下载 vosk-model-small-cn-0.22 (中文轻量模型，约40MB)\n" +
                        "3. 解压到项目根目录的 models 文件夹下\n" +
                        "4. 或点击「设置」选择模型所在文件夹\n" +
                        "5. 重启软件或重新勾选启用语音识别");
        alert.showAndWait();
    }

    private void openOutputFolder() {
        File folder = new File("recordings");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        try {
            Desktop.getDesktop().open(folder);
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "错误", "无法打开文件夹: " + e.getMessage());
        }
    }

    private void updateButtonStates(boolean isRecording, boolean isPaused) {
        btnRecord.setDisable(isRecording);
        btnPause.setDisable(!isRecording);
        btnStop.setDisable(!isRecording);
        btnSelectArea.setDisable(isRecording);
        btnSettings.setDisable(isRecording);
        btnOpenFolder.setDisable(isRecording);
        chkFullScreen.setDisable(isRecording);
        chkEnableASR.setDisable(isRecording);
    }

    private void updateStatus(String text, Color color) {
        lblStatus.setText(text);
        lblStatus.setTextFill(color);
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
