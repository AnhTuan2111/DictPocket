package com.anhtuan.dict.desktop.ui;

import javafx.css.PseudoClass;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

// Khung cửa sổ kiểu Win9x tự vẽ: thanh tiêu đề, nút thu nhỏ / phóng to / đóng, kéo và đổi cỡ.
// Thay cho khung hệ điều hành nên chỉ cần một lớp nhỏ, không thêm thư viện nào.
public final class WindowChrome
{
    private static final PseudoClass INACTIVE = PseudoClass.getPseudoClass("inactive");
    private static final double EDGE = 6;
    private static final double MIN_WIDTH = 520;
    private static final double MIN_HEIGHT = 360;

    private static final int LEFT = 1;
    private static final int RIGHT = 2;
    private static final int TOP = 4;
    private static final int BOTTOM = 8;

    private final Stage stage;
    private final Scene scene;
    private final VBox frame = new VBox();

    private double grabX;
    private double grabY;

    private int resizeEdges;
    private double startX;
    private double startY;
    private double startWidth;
    private double startHeight;
    private double startScreenX;
    private double startScreenY;

    // Khác null khi cửa sổ đang phóng to, giữ kích thước cũ để khôi phục
    private Rectangle2D restoreBounds;

    private WindowChrome(Stage stage, Node icon, String title, Region content, double width, double height,
            boolean resizable)
    {
        this.stage = stage;
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle(title);

        HBox titleBar = new HBox(4);
        titleBar.getStyleClass().add("title-bar");
        Label text = new Label(title);
        text.getStyleClass().add("title-text");
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        if (icon != null)
        {
            titleBar.getChildren().add(icon);
        }
        titleBar.getChildren().add(text);
        if (resizable)
        {
            titleBar.getChildren().add(captionButton("glyph-min", () -> stage.setIconified(true)));
            titleBar.getChildren().add(captionButton("glyph-max", this::toggleMaximize));
        }
        titleBar.getChildren().add(captionButton("glyph-close", stage::close));

        titleBar.setOnMousePressed(e ->
        {
            grabX = e.getScreenX() - stage.getX();
            grabY = e.getScreenY() - stage.getY();
        });
        titleBar.setOnMouseDragged(e ->
        {
            if (restoreBounds == null)
            {
                stage.setX(e.getScreenX() - grabX);
                stage.setY(e.getScreenY() - grabY);
            }
        });
        if (resizable)
        {
            titleBar.setOnMouseClicked(e ->
            {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2)
                {
                    toggleMaximize();
                }
            });
        }

        frame.getStyleClass().add("window");
        frame.getChildren().addAll(titleBar, content);
        VBox.setVgrow(content, Priority.ALWAYS);

        scene = new Scene(frame, width, height, Color.web("#c0c0c0"));
        scene.getStylesheets().add(WindowChrome.class.getResource("/css/dict.css").toExternalForm());
        stage.setScene(scene);

        // Cửa sổ mất tiêu điểm thì thanh tiêu đề chuyển xám
        stage.focusedProperty().addListener((obs, was, focused) -> frame.pseudoClassStateChanged(INACTIVE, !focused));

        if (resizable)
        {
            installEdgeResize();
        }
    }

    public static WindowChrome install(Stage stage, Node icon, String title, Region content, double width,
            double height, boolean resizable)
    {
        return new WindowChrome(stage, icon, title, content, width, height, resizable);
    }

    public Scene scene()
    {
        return scene;
    }

    // Tay nắm ở góc dưới phải của thanh trạng thái
    public void bindGrip(Node grip)
    {
        grip.setCursor(Cursor.SE_RESIZE);
        grip.setOnMousePressed(e ->
        {
            if (restoreBounds == null)
            {
                beginResize(e, RIGHT | BOTTOM);
            }
        });
        grip.setOnMouseDragged(e ->
        {
            if (resizeEdges != 0)
            {
                resize(e);
            }
        });
        grip.setOnMouseReleased(e -> resizeEdges = 0);
    }

    private static Button captionButton(String glyphClass, Runnable action)
    {
        Region glyph = new Region();
        glyph.getStyleClass().addAll("glyph", glyphClass);
        Button button = new Button();
        button.setGraphic(glyph);
        button.getStyleClass().add("caption-button");
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    private void toggleMaximize()
    {
        if (restoreBounds != null)
        {
            stage.setX(restoreBounds.getMinX());
            stage.setY(restoreBounds.getMinY());
            stage.setWidth(restoreBounds.getWidth());
            stage.setHeight(restoreBounds.getHeight());
            restoreBounds = null;
            return;
        }
        restoreBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        // Dùng vùng nhìn thấy được để không đè lên thanh taskbar
        var screens = Screen.getScreensForRectangle(restoreBounds);
        Rectangle2D area = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
        stage.setX(area.getMinX());
        stage.setY(area.getMinY());
        stage.setWidth(area.getWidth());
        stage.setHeight(area.getHeight());
    }

    private void installEdgeResize()
    {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED,
                e -> scene.setCursor(restoreBounds == null ? cursorFor(edgesAt(e)) : Cursor.DEFAULT));
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e ->
        {
            int edges = restoreBounds == null ? edgesAt(e) : 0;
            if (edges != 0)
            {
                beginResize(e, edges);
                e.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e ->
        {
            if (resizeEdges != 0)
            {
                resize(e);
                e.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> resizeEdges = 0);
    }

    private int edgesAt(MouseEvent e)
    {
        int edges = 0;
        if (e.getSceneX() < EDGE)
        {
            edges |= LEFT;
        }
        else if (e.getSceneX() > scene.getWidth() - EDGE)
        {
            edges |= RIGHT;
        }
        if (e.getSceneY() < EDGE)
        {
            edges |= TOP;
        }
        else if (e.getSceneY() > scene.getHeight() - EDGE)
        {
            edges |= BOTTOM;
        }
        return edges;
    }

    private static Cursor cursorFor(int edges)
    {
        return switch (edges)
        {
            case LEFT, RIGHT -> Cursor.H_RESIZE;
            case TOP, BOTTOM -> Cursor.V_RESIZE;
            case LEFT | TOP -> Cursor.NW_RESIZE;
            case RIGHT | BOTTOM -> Cursor.SE_RESIZE;
            case RIGHT | TOP -> Cursor.NE_RESIZE;
            case LEFT | BOTTOM -> Cursor.SW_RESIZE;
            default -> Cursor.DEFAULT;
        };
    }

    private void beginResize(MouseEvent e, int edges)
    {
        resizeEdges = edges;
        startX = stage.getX();
        startY = stage.getY();
        startWidth = stage.getWidth();
        startHeight = stage.getHeight();
        startScreenX = e.getScreenX();
        startScreenY = e.getScreenY();
    }

    private void resize(MouseEvent e)
    {
        double dx = e.getScreenX() - startScreenX;
        double dy = e.getScreenY() - startScreenY;
        double x = startX;
        double y = startY;
        double width = startWidth;
        double height = startHeight;
        if ((resizeEdges & RIGHT) != 0)
        {
            width = Math.max(MIN_WIDTH, startWidth + dx);
        }
        if ((resizeEdges & BOTTOM) != 0)
        {
            height = Math.max(MIN_HEIGHT, startHeight + dy);
        }
        if ((resizeEdges & LEFT) != 0)
        {
            width = Math.max(MIN_WIDTH, startWidth - dx);
            x = startX + startWidth - width;
        }
        if ((resizeEdges & TOP) != 0)
        {
            height = Math.max(MIN_HEIGHT, startHeight - dy);
            y = startY + startHeight - height;
        }
        stage.setX(x);
        stage.setY(y);
        stage.setWidth(width);
        stage.setHeight(height);
    }
}
