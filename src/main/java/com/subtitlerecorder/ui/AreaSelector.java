package com.subtitlerecorder.ui;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
// import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * 区域选择器 - 用于拖拽选择录屏区域
 */
public class AreaSelector {
    private Stage stage;
    private Consumer<Rectangle> onAreaSelected;
    private javafx.scene.shape.Rectangle selectionRect;
    private double startX, startY;
    private Label hintLabel;

    public AreaSelector() {}

    public AreaSelector(Consumer<Rectangle> callback) {
        this.onAreaSelected = callback;
    }

    public void start() {
        // 先截取全屏作为背景
        try {
            Robot robot = new Robot();
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage screenCapture = robot.createScreenCapture(screenRect);
            WritableImage fxImage = SwingFXUtils.toFXImage(screenCapture, null);

            ImageView imageView = new ImageView(fxImage);
            Pane root = new Pane(imageView);

            // 半透明黑色遮罩
            root.setStyle("-fx-background-color: rgba(0,0,0,0.3);");

            Scene scene = new Scene(root, screenRect.width, screenRect.height);
            scene.setFill(Color.TRANSPARENT);
            scene.setCursor(javafx.scene.Cursor.CROSSHAIR);

            stage = new Stage();
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setAlwaysOnTop(true);
            stage.setScene(scene);

            // 选择框
            selectionRect = new javafx.scene.shape.Rectangle();
            selectionRect.setFill(Color.color(0.2, 0.6, 1.0, 0.2));
            selectionRect.setStroke(Color.color(0.2, 0.6, 1.0, 0.9));
            selectionRect.setStrokeWidth(2);
            selectionRect.getStrokeDashArray().addAll(6.0, 4.0);
            selectionRect.setVisible(false);
            root.getChildren().add(selectionRect);

            // 提示文字
            hintLabel = new Label("拖拽鼠标选择录制区域\n按 ESC 取消");
            hintLabel.setStyle(
                    "-fx-font-size: 20px; -fx-text-fill: white; -fx-background-color: rgba(0,0,0,0.7); -fx-padding: 15 25; -fx-background-radius: 8;");
            hintLabel.setLayoutX((screenRect.width - 300) / 2);
            hintLabel.setLayoutY(50);
            root.getChildren().add(hintLabel);

            // 鼠标事件
            scene.setOnMousePressed(this::onMousePressed);
            scene.setOnMouseDragged(this::onMouseDragged);
            scene.setOnMouseReleased(this::onMouseReleased);

            // ESC取消
            scene.setOnKeyPressed(e -> {
                if (e.getCode().toString().equals("ESCAPE")) {
                    close(null);
                }
            });

            stage.show();

        } catch (AWTException e) {
            e.printStackTrace();
            close(null);
        }
    }

    private void onMousePressed(MouseEvent e) {
        startX = e.getX();
        startY = e.getY();
        hintLabel.setVisible(false);

        selectionRect.setX(startX);
        selectionRect.setY(startY);
        selectionRect.setWidth(0);
        selectionRect.setHeight(0);
        selectionRect.setVisible(true);
    }

    private void onMouseDragged(MouseEvent e) {
        double x = Math.min(startX, e.getX());
        double y = Math.min(startY, e.getY());
        double w = Math.abs(e.getX() - startX);
        double h = Math.abs(e.getY() - startY);

        selectionRect.setX(x);
        selectionRect.setY(y);
        selectionRect.setWidth(w);
        selectionRect.setHeight(h);
    }

    private void onMouseReleased(MouseEvent e) {
        if (selectionRect.getWidth() > 20 && selectionRect.getHeight() > 20) {
            int x = (int) selectionRect.getX();
            int y = (int) selectionRect.getY();
            int w = (int) selectionRect.getWidth();
            int h = (int) selectionRect.getHeight();

            close(new Rectangle(x, y, w, h));
        } else {
            // 点击太小，取消
            close(null);
        }
    }

    private void close(Rectangle rect) {
        if (stage != null) {
            stage.close();
        }
        if (onAreaSelected != null) {
            onAreaSelected.accept(rect);
        }
    }
}