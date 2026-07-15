import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ByteProcessor;

import java.awt.Button;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextArea;
import java.awt.TextField;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class MeasureLineIntegrationCheck {
    public static void main(String[] args) throws Exception {
        new ImageJ();
        Measure_line plugin = new Measure_line();
        plugin.setup("", null);
        plugin.run(null);
        flushEdt();

        Frame controls = findControls();
        require(controls != null, "Control window did not open without an image");
        require(collectLabels(controls).contains("Frequencies (kHz):"),
            "Frequencies label is missing");
        require(!collectLabels(controls).contains("Targets (kHz):"),
            "Old Targets label remains");
        require(collectText(controls).contains("Esc  Cancel current segment"),
            "Escape shortcut is missing from the mini manual");
        require(collectText(controls).contains("Ctrl/Cmd+Z  Undo last action"),
            "Undo shortcut is missing from the mini manual");
        require(collectButtons(controls).contains("Close current image"),
            "Close current image button is missing");
        require(collectButtons(controls).contains("Toggle point mode"),
            "Toggle point mode button is missing");
        require(collectText(controls).contains("P  Toggle point mode"),
            "Point-mode shortcut wording is incorrect");
        require(!collectLabels(controls).contains("(blank = image scale)"),
            "Old scale hint remains");
        assertLeftAligned(controls);
        assertRowStartsAligned(controls);

        TextField frequencies = (TextField) field(plugin, "targetField");
        require("4, 5.6, 8, 11.3, 16, 22.6, 32, 45.2, 64".equals(frequencies.getText()),
            "Default frequencies are incorrect: " + frequencies.getText());
        Label scaleDisplay = (Label) field(plugin, "scaleDisplayLabel");
        require("No image scale".equals(scaleDisplay.getText()),
            "No-image scale text is incorrect: " + scaleDisplay.getText());

        ImagePlus first = new ImagePlus("Calibrated image", new ByteProcessor(600, 400));
        Calibration calibration = first.getCalibration();
        calibration.pixelWidth = 1.375;
        calibration.pixelHeight = 1.375;
        calibration.setUnit("microns");
        first.setCalibration(calibration);
        first.show();
        flushEdt();
        require("1.375 microns/pixel".equals(scaleDisplay.getText()),
            "Native scale display is incorrect: " + scaleDisplay.getText());

        TextField scale = (TextField) field(plugin, "scaleField");
        TextField unit = (TextField) field(plugin, "unitField");
        scale.setText("2.5");
        unit.setText("mm");
        flushEdt();
        require("2.5 mm/pixel".equals(scaleDisplay.getText()),
            "Custom scale display is incorrect: " + scaleDisplay.getText());
        scale.setText("");
        unit.setText("");
        flushEdt();
        require("1.375 microns/pixel".equals(scaleDisplay.getText()),
            "Blank fields did not restore native scale display");

        storeSegment(plugin, first, 20, 60);
        storeSegment(plugin, first, 80, 120);
        require(intField(plugin, "numPieces") == 2, "Two segments were not stored");

        invoke(plugin, "annotate");
        require(booleanField(plugin, "annotationVisible"), "Annotate action was not tracked");
        invoke(plugin, "togglePointMode");
        addPointLabel(plugin, first, 0, 0);
        addPointLabel(plugin, first, 1, 0);
        require(listSize(plugin, "pointAnnotations") == 2, "Point labels were not tracked individually");

        first.setRoi(new PolygonRoi(
            new int[] {150, 180, 210}, new int[] {80, 110, 80}, 3, Roi.POLYLINE));
        press(plugin, first, KeyEvent.VK_ESCAPE, 0);
        require(first.getRoi() == null, "Escape did not cancel the unfinished segment");
        require(intField(plugin, "numPieces") == 2, "Escape removed a stored segment");
        require(booleanField(plugin, "picking"), "Escape exited point mode");

        press(plugin, first, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        require(listSize(plugin, "pointAnnotations") == 1, "Ctrl+Z did not remove one point label");
        press(plugin, first, KeyEvent.VK_Z, InputEvent.META_DOWN_MASK);
        require(listSize(plugin, "pointAnnotations") == 0, "Cmd+Z did not remove one point label");
        press(plugin, first, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        require(!booleanField(plugin, "annotationVisible"), "Undo did not remove the annotation action");
        require(intField(plugin, "numPieces") == 2, "Annotation undo removed a segment");
        press(plugin, first, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        require(intField(plugin, "numPieces") == 1, "Undo did not remove the newest segment");
        press(plugin, first, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        require(intField(plugin, "numPieces") == 0, "Repeated undo did not remove the first segment");

        storeSegment(plugin, first, 20, 60);
        invoke(plugin, "clearAnnotations");
        press(plugin, first, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        require(intField(plugin, "numPieces") == 1, "Clear annotations did not reset undo history");
        invoke(plugin, "clearAll");
        require(intField(plugin, "numPieces") == 0, "Clear all did not reset segments");

        storeSegment(plugin, first, 20, 60);
        storeSegment(plugin, first, 80, 120);
        invoke(plugin, "clearLastSegment");
        press(plugin, first, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        require(intField(plugin, "numPieces") == 1, "Clear last segment remained undoable");

        ImagePlus second = new ImagePlus("Uncalibrated image", new ByteProcessor(300, 200));
        second.show();
        flushEdt();
        require(listSize(plugin, "undoActions") == 0, "Image change did not reset undo history");
        require("1 pixel/pixel".equals(scaleDisplay.getText()),
            "Uncalibrated scale display is incorrect: " + scaleDisplay.getText());

        first.changes = false;
        second.changes = false;
        WindowManager.setCurrentWindow(first.getWindow());
        plugin.keyTyped(new KeyEvent(second.getCanvas(), KeyEvent.KEY_TYPED,
            System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'c'));
        flushEdt();
        require(first.getWindow() == null, "C did not close Fiji's last-focused image");
        require(second.getWindow() != null, "C closed the plugin-attached image instead");

        System.out.println("Measure line undo, focus, layout, frequency, and scale checks passed");
        System.exit(0);
    }

    private static void storeSegment(Measure_line plugin, ImagePlus image, int startX, int endX)
            throws Exception {
        image.setRoi(new PolygonRoi(
            new int[] {startX, (startX + endX) / 2, endX},
            new int[] {60, 90, 60}, 3, Roi.POLYLINE));
        plugin.mouseClicked(new MouseEvent(image.getCanvas(), MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(), 0, (startX + endX) / 2, 90, 2, false));
        flushEdt();
    }

    private static void addPointLabel(Measure_line plugin, ImagePlus image, int piece, int point)
            throws Exception {
        double[][] x = (double[][]) field(plugin, "xCoords");
        double[][] y = (double[][]) field(plugin, "yCoords");
        ImageCanvas canvas = image.getCanvas();
        plugin.mouseClicked(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(), 0,
            canvas.screenX((int) x[piece][point]), canvas.screenY((int) y[piece][point]),
            1, false));
    }

    private static void press(Measure_line plugin, ImagePlus image, int keyCode, int modifiers) {
        plugin.keyPressed(new KeyEvent(image.getCanvas(), KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(), modifiers, keyCode, KeyEvent.CHAR_UNDEFINED));
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int intField(Object target, String name) throws Exception {
        return ((Integer) field(target, name)).intValue();
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        return ((Boolean) field(target, name)).booleanValue();
    }

    private static int listSize(Object target, String name) throws Exception {
        return ((List<?>) field(target, name)).size();
    }

    private static Frame findControls() {
        for (Frame frame : Frame.getFrames()) {
            if ("Measure line".equals(frame.getTitle()) && frame.isVisible()) {
                return frame;
            }
        }
        return null;
    }

    private static List<String> collectButtons(Container container) {
        List<String> values = new ArrayList<String>();
        for (Component component : container.getComponents()) {
            if (component instanceof Button) values.add(((Button) component).getLabel());
            if (component instanceof Container) values.addAll(collectButtons((Container) component));
        }
        return values;
    }

    private static String collectLabels(Container container) {
        StringBuilder values = new StringBuilder();
        for (Component component : container.getComponents()) {
            if (component instanceof Label) values.append(((Label) component).getText()).append('\n');
            if (component instanceof Container) values.append(collectLabels((Container) component));
        }
        return values.toString();
    }

    private static String collectText(Container container) {
        StringBuilder values = new StringBuilder();
        for (Component component : container.getComponents()) {
            if (component instanceof TextArea) values.append(((TextArea) component).getText());
            if (component instanceof Container) values.append(collectText((Container) component));
        }
        return values.toString();
    }

    private static void assertLeftAligned(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof Panel && ((Panel) component).getLayout() instanceof FlowLayout) {
                FlowLayout layout = (FlowLayout) ((Panel) component).getLayout();
                require(layout.getAlignment() == FlowLayout.LEFT,
                    "Panel is not left aligned: " + component);
            }
            if (component instanceof Container) assertLeftAligned((Container) component);
        }
    }

    private static void assertRowStartsAligned(Frame controls) {
        GridBagLayout layout = (GridBagLayout) controls.getLayout();
        Integer expectedX = null;
        for (Component component : controls.getComponents()) {
            GridBagConstraints constraints = layout.getConstraints(component);
            if (constraints.gridx != 0) continue;
            int x = component.getX();
            if (component instanceof Panel && ((Panel) component).getComponentCount() > 0) {
                x += ((Panel) component).getComponent(0).getX();
            }
            if (expectedX == null) expectedX = Integer.valueOf(x);
            require(expectedX.intValue() == x,
                "Row starts at x=" + x + " instead of x=" + expectedX + ": " + component);
        }
    }

    private static void flushEdt() throws Exception {
        EventQueue.invokeAndWait(new Runnable() { public void run() { } });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
