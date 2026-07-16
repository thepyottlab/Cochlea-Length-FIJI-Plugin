import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.io.DirectoryChooser;
import ij.io.FileInfo;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.measure.Calibration;
import ij.plugin.filter.PlugInFilter;
import ij.process.Blitter;
import ij.process.ImageProcessor;
import ij.text.TextWindow;

import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Label;
import java.awt.Panel;
import java.awt.Rectangle;
import java.awt.TextArea;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.TextEvent;
import java.awt.event.TextListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import loci.formats.ImageReader;

/**
 * EPL Measure_line plugin with a persistent control window.
 *
 * The cochlear frequency-place functions and tracing behavior come from the
 * original Eaton-Peabody Laboratories Measure_line.class.
 */
public class Measure_line implements PlugInFilter, MouseListener,
        MouseMotionListener, KeyListener, ActionListener, ItemListener, TextListener, ImageListener {

    private static Measure_line activeInstance;

    private static final int MAX_PIECES = 100;
    private static final int CAT = 0;
    private static final int GUINEA_PIG = 1;
    private static final int CHINCHILLA = 2;
    private static final int HUMAN = 3;
    private static final int MOUSE = 4;
    private static final int RAT = 5;
    private static final int RHESUS_MONKEY = 6;
    private static final int GERBIL = 7;
    private static final int MARMOSET = 8;
    private static final int EQUAL_DIVISIONS = 9;
    private static final int UNDO_SEGMENT = 1;
    private static final int UNDO_ANNOTATION = 2;
    private static final int UNDO_POINT = 3;

    private static final String[] MODES = {
        "Cat", "Guinea pig", "Chinchilla", "Human", "Mouse", "Rat",
        "Rhesus monkey", "Gerbil", "Marmoset", "Equal divisions"
    };

    private static final String DEFAULT_TARGETS =
        "4, 5.6, 8, 11.3, 16, 22.6, 32, 45.2, 64";

    private static final Pattern FOUR_DIGIT_SUFFIX =
        Pattern.compile(".*\\d{4}(?:\\.[^.]+)+$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> IMAGE_EXTENSIONS = loadImageExtensions();
    private static final Set<String> HEADER_CHECK_EXTENSIONS = new HashSet<String>(Arrays.asList(
        "txt", "xml", "csv", "json", "log", "ini", "cfg", "html", "htm"));

    private ImageWindow imageWindow;
    private ImagePlus image;
    private ImagePlus startupImage;
    private ImageProcessor processor;
    private ImageCanvas canvas;
    private ImageProcessor cleanProcessor;
    private ImageProcessor traceProcessor;
    private ImageProcessor pointBaseProcessor;
    private KeyListener[] oldKeyListeners;

    private final int[] pointCounts = new int[MAX_PIECES];
    private final double[][] xCoords = new double[MAX_PIECES][];
    private final double[][] yCoords = new double[MAX_PIECES][];
    private final double[][] pixelLengths = new double[MAX_PIECES][];
    private final double[][] frequencies = new double[MAX_PIECES][];
    private final Rectangle[] bounds = new Rectangle[MAX_PIECES];
    private final List<Integer> undoActions = new ArrayList<Integer>();
    private final List<PointAnnotation> pointAnnotations = new ArrayList<PointAnnotation>();

    private int numPieces;
    private int currentPiece;
    private double totalPixelLength = -1;
    private boolean calculated;
    private boolean picking;
    private boolean annotationVisible;
    private int species = MOUSE;
    private int numberOfDivisions = 20;
    private double customScale = Double.NaN;
    private String customUnit = "";
    private double[][] divisionPoints = new double[numberOfDivisions][4];
    private double[] targetFrequencies = parseTargetFrequencies(DEFAULT_TARGETS);
    private double[][] targetPoints = new double[targetFrequencies.length][2];

    private Calibration calibration;
    private Font labelFont;
    private int fixedLineWidth = 1;
    private int dynamicLineWidth;
    private double divisionLineLength;

    private Frame controls;
    private Choice animalChoice;
    private TextField divisionField;
    private TextField targetField;
    private TextField scaleField;
    private TextField unitField;
    private Label scaleDisplayLabel;
    private Button annotateButton;
    private Button measureButton;
    private Button pointButton;
    private Button clearAnnotationsButton;
    private Button clearLastButton;
    private Button clearAllButton;
    private Button chooseFolderButton;
    private Button loadButton;
    private Button loadNextButton;
    private Button closeImageButton;
    private Button saveButton;
    private Button refreshButton;
    private TextField folderField;
    private TextField suffixField;
    private Checkbox addLengthCheckbox;
    private Checkbox hideFourDigitCheckbox;
    private Checkbox hideMappedCheckbox;
    private java.awt.List imageList;
    private String selectedFolder;
    private File activeSourceFile;
    private Label statusLabel;

    @Override
    public int setup(String arg, ImagePlus imp) {
        startupImage = imp;
        return DOES_8G | DOES_16 | DOES_32 | DOES_RGB | NO_IMAGE_REQUIRED;
    }

    @Override
    public void run(ImageProcessor ip) {
        ImagePlus initialImage = startupImage != null
            ? startupImage : WindowManager.getCurrentImage();
        synchronized (Measure_line.class) {
            if (activeInstance != null) {
                if (initialImage != null) {
                    activeInstance.attachToImage(initialImage);
                }
                activeInstance.showControls();
                if (initialImage != null) {
                    activeInstance.selectSegmentedLine();
                }
                return;
            }
            activeInstance = this;
        }

        ImagePlus.addImageListener(this);
        if (initialImage != null) {
            attachToImage(initialImage);
        }
        showControls();
        if (image != null) {
            setStatus("Active: " + image.getTitle());
            selectSegmentedLine();
        } else {
            setStatus("Waiting for an image");
        }
    }

    private boolean attachToImage(ImagePlus nextImage) {
        if (nextImage == null) {
            return false;
        }
        ImageWindow nextWindow = nextImage.getWindow();
        if (nextWindow == null || nextWindow.getCanvas() == null) {
            setStatus("Waiting for an image window");
            return false;
        }
        if (nextImage == image && canvas == nextWindow.getCanvas()) {
            setStatus("Active: " + image.getTitle());
            return true;
        }

        detachCanvas();
        image = nextImage;
        imageWindow = nextWindow;
        canvas = nextWindow.getCanvas();
        processor = nextImage.getProcessor();
        activeSourceFile = sourceFile(nextImage);

        int largestSide = Math.max(nextImage.getWidth(), nextImage.getHeight());
        dynamicLineWidth = Math.max(1, largestSide / 100);
        divisionLineLength = Math.max(5, largestSide / 50.0);
        labelFont = new Font("SansSerif", Font.PLAIN,
            Math.max(10, dynamicLineWidth * 2));

        oldKeyListeners = canvas.getKeyListeners();
        for (KeyListener listener : oldKeyListeners) {
            canvas.removeKeyListener(listener);
        }
        canvas.addKeyListener(this);
        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);

        cleanProcessor = processor.duplicate();
        traceProcessor = cleanProcessor.duplicate();
        resetTraceData();
        updateScaleDisplay();
        setStatus("Active: " + nextImage.getTitle());
        selectSegmentedLine();
        return true;
    }

    private void detachCanvas() {
        if (canvas == null) {
            return;
        }
        canvas.removeKeyListener(this);
        canvas.removeMouseListener(this);
        canvas.removeMouseMotionListener(this);
        if (oldKeyListeners != null) {
            for (KeyListener listener : oldKeyListeners) {
                boolean present = false;
                for (KeyListener current : canvas.getKeyListeners()) {
                    present |= current == listener;
                }
                if (!present) {
                    canvas.addKeyListener(listener);
                }
            }
        }
        oldKeyListeners = null;
        canvas = null;
    }

    private void showControls() {
        if (controls != null) {
            controls.setVisible(true);
            controls.toFront();
            return;
        }

        controls = new Frame("Measure line");
        controls.setLayout(new GridBagLayout());
        controls.setResizable(false);
        controls.addKeyListener(this);
        controls.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                removePlugin();
            }
        });

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(8, 8, 4, 8);
        controls.add(new Label(
            "Click along each piece from base to apex. Double click to mark the end of a piece."), c);

        TextArea help = new TextArea(
            "A  Annotate targets or divisions\n"
            + "M  Show length measurements\n"
            + "P  Toggle point mode\n"
            + "S  Save (optionally with suffix)\n"
            + "N  Load next image\n"
            + "C  Close current image\n"
            + "Esc  Cancel current segment\n"
            + "Ctrl/Cmd+Z  Undo last action",
            8, 46, TextArea.SCROLLBARS_NONE);
        help.setEditable(false);
        c.gridy++;
        c.insets = new Insets(0, 8, 8, 8);
        controls.add(help, c);

        c.gridwidth = 1;
        c.gridy++;
        c.insets = new Insets(2, 8, 2, 4);
        c.fill = GridBagConstraints.NONE;
        controls.add(new Label("Animal / mode:"), c);

        animalChoice = new Choice();
        for (String mode : MODES) {
            animalChoice.add(mode);
        }
        animalChoice.select(MOUSE);
        animalChoice.addItemListener(this);
        c.gridx = 1;
        c.insets = new Insets(2, 4, 2, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        controls.add(animalChoice, c);

        c.gridx = 0;
        c.gridy++;
        c.insets = new Insets(2, 8, 2, 4);
        c.fill = GridBagConstraints.NONE;
        controls.add(new Label("Equal divisions:"), c);

        divisionField = new TextField("20", 8);
        divisionField.addActionListener(this);
        c.gridx = 1;
        c.insets = new Insets(2, 4, 2, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        controls.add(divisionField, c);

        c.gridx = 0;
        c.gridy++;
        c.insets = new Insets(2, 8, 2, 4);
        c.fill = GridBagConstraints.NONE;
        controls.add(new Label("Frequencies (kHz):"), c);

        targetField = new TextField(DEFAULT_TARGETS, 46);
        targetField.addActionListener(this);
        c.gridx = 1;
        c.insets = new Insets(2, 4, 2, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        controls.add(targetField, c);

        Panel scalePanel = new Panel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        scalePanel.add(new Label("Scale (per pixel):"));
        scaleField = new TextField("", 8);
        scaleField.addActionListener(this);
        scaleField.addTextListener(this);
        scalePanel.add(scaleField);
        scalePanel.add(new Label("Unit:"));
        unitField = new TextField("", 10);
        unitField.addActionListener(this);
        unitField.addTextListener(this);
        scalePanel.add(unitField);
        scaleDisplayLabel = new Label("No image scale");
        scalePanel.add(scaleDisplayLabel);
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.insets = new Insets(2, 2, 2, 8);
        controls.add(scalePanel, c);

        addLengthCheckbox = new Checkbox("Add length when annotating", true);
        c.gridy++;
        c.insets = new Insets(2, 8, 4, 8);
        controls.add(addLengthCheckbox, c);

        Panel firstRow = new Panel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        annotateButton = addButton(firstRow, "Annotate");
        measureButton = addButton(firstRow, "Show length");
        pointButton = addButton(firstRow, "Toggle point mode");
        c.gridy++;
        c.insets = new Insets(8, 2, 2, 8);
        controls.add(firstRow, c);

        Panel secondRow = new Panel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        clearAnnotationsButton = addButton(secondRow, "Clear annotations");
        clearLastButton = addButton(secondRow, "Clear last segment");
        clearAllButton = addButton(secondRow, "Clear all");
        c.gridy++;
        c.insets = new Insets(2, 2, 4, 8);
        controls.add(secondRow, c);

        Panel folderPanel = new Panel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        chooseFolderButton = addButton(folderPanel, "Choose image folder");
        folderField = new TextField("", 24);
        folderField.setEditable(false);
        folderPanel.add(folderField);
        hideFourDigitCheckbox = new Checkbox("Hide tiles", true);
        hideFourDigitCheckbox.addItemListener(this);
        folderPanel.add(hideFourDigitCheckbox);
        hideMappedCheckbox = new Checkbox("Hide mapped", false);
        hideMappedCheckbox.addItemListener(this);
        folderPanel.add(hideMappedCheckbox);
        refreshButton = addButton(folderPanel, "Refresh");
        c.gridy++;
        c.insets = new Insets(2, 2, 2, 8);
        controls.add(folderPanel, c);

        imageList = new java.awt.List(7, false);
        imageList.addActionListener(this);
        c.gridy++;
        c.insets = new Insets(2, 8, 2, 8);
        controls.add(imageList, c);

        Panel fileButtons = new Panel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        loadButton = addButton(fileButtons, "Load selected");
        loadNextButton = addButton(fileButtons, "Load next");
        closeImageButton = addButton(fileButtons, "Close current image");
        fileButtons.add(new Label("Save suffix:"));
        suffixField = new TextField("-mapped", 12);
        fileButtons.add(suffixField);
        saveButton = addButton(fileButtons, "Save image");
        c.gridy++;
        c.insets = new Insets(2, 2, 2, 8);
        controls.add(fileButtons, c);

        statusLabel = new Label("Ready");
        c.gridy++;
        c.insets = new Insets(0, 8, 8, 8);
        controls.add(statusLabel, c);

        updateEnabledFields();
        updateScaleDisplay();
        controls.pack();
        if (imageWindow != null) {
            controls.setLocation(imageWindow.getX() + imageWindow.getWidth() + 10,
                imageWindow.getY());
        } else {
            controls.setLocationByPlatform(true);
        }
        controls.setVisible(true);
    }

    private Button addButton(Panel panel, String label) {
        Button button = new Button(label);
        button.addActionListener(this);
        button.addKeyListener(this);
        panel.add(button);
        return button;
    }

    private void selectSegmentedLine() {
        if (!IJ.setTool("polyline")) {
            IJ.setTool(Toolbar.POLYLINE);
        }
        if (canvas != null) {
            canvas.requestFocus();
        }
    }

    @Override
    public void itemStateChanged(ItemEvent event) {
        if (event.getSource() == hideFourDigitCheckbox
                || event.getSource() == hideMappedCheckbox) {
            refreshImageList(null);
            return;
        }
        if (event.getSource() == addLengthCheckbox) {
            return;
        }
        calculated = false;
        updateEnabledFields();
    }

    @Override
    public void textValueChanged(TextEvent event) {
        updateScaleDisplay();
    }

    private void updateEnabledFields() {
        if (animalChoice == null) {
            return;
        }
        boolean equal = animalChoice.getSelectedIndex() == EQUAL_DIVISIONS;
        divisionField.setEnabled(equal);
        targetField.setEnabled(!equal);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        Object source = event.getSource();
        if (source == chooseFolderButton) {
            chooseFolder();
        } else if (source == refreshButton) {
            refreshImageList(imageList.getSelectedItem());
        } else if (source == loadButton || source == imageList) {
            loadSelectedImage();
        } else if (source == loadNextButton) {
            loadNextImage();
        } else if (source == closeImageButton) {
            closeCurrentImage();
        } else if (source == saveButton) {
            saveImage();
        } else if (source == clearAnnotationsButton) {
            clearAnnotations();
        } else if (source == clearLastButton) {
            clearLastSegment();
        } else if (source == clearAllButton) {
            clearAll();
        } else if (source == annotateButton) {
            annotate();
        } else if (source == measureButton) {
            showMeasurements();
        } else if (source == pointButton) {
            togglePointMode();
        } else {
            calculated = false;
            applySettings();
        }
    }

    private void chooseFolder() {
        String folder = new DirectoryChooser("Choose image folder").getDirectory();
        if (folder == null) {
            return;
        }
        selectedFolder = folder;
        folderField.setText(folder);
        refreshImageList(null);
        setStatus("Selected folder");
    }

    private static Set<String> loadImageExtensions() {
        Set<String> extensions = new HashSet<String>(Arrays.asList(
            "tif", "tiff", "png", "jpg", "jpeg", "gif", "bmp", "pgm", "fits", "dcm", "dicom", "avi"));
        try {
            for (String suffix : new ImageReader().getSuffixes()) {
                extensions.add(suffix.toLowerCase());
            }
        } catch (Throwable ignored) {
            // Fiji without Bio-Formats still supports its native image formats.
        }
        return extensions;
    }

    private void refreshImageList(String selectPath) {
        imageList.removeAll();
        if (selectedFolder == null) {
            return;
        }
        List<File> files = new ArrayList<File>();
        collectImages(new File(selectedFolder), files);
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return relativePath(first).compareToIgnoreCase(relativePath(second));
            }
        });
        Set<String> hiddenMappedStems = new HashSet<String>();
        String mappedSuffix = suffixField.getText().trim();
        if (hideMappedCheckbox.getState() && mappedSuffix.length() > 0) {
            for (File file : files) {
                String stem = fileStem(file);
                if (stem.endsWith(mappedSuffix)) {
                    hiddenMappedStems.add(stemKey(file, stem));
                    hiddenMappedStems.add(stemKey(file,
                        stem.substring(0, stem.length() - mappedSuffix.length())));
                }
            }
        }
        for (File file : files) {
            if (hideFourDigitCheckbox.getState() && FOUR_DIGIT_SUFFIX.matcher(file.getName()).matches()) {
                continue;
            }
            if (hiddenMappedStems.contains(stemKey(file, fileStem(file)))) {
                continue;
            }
            String relative = relativePath(file);
            imageList.add(relative);
            if (relative.equals(selectPath)) {
                imageList.select(imageList.getItemCount() - 1);
            }
        }
    }

    private void collectImages(File directory, List<File> files) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory() && !Files.isSymbolicLink(child.toPath())) {
                collectImages(child, files);
            } else if (child.isFile() && !child.getName().startsWith("._")) {
                String extension = extensionOf(child.getName());
                String suffix = extension.length() > 1 ? extension.substring(1) : "";
                if (IMAGE_EXTENSIONS.contains(suffix)
                        && (!HEADER_CHECK_EXTENSIONS.contains(suffix) || isBioFormatsImage(child))) {
                    files.add(child);
                }
            }
        }
    }

    private static boolean isBioFormatsImage(File file) {
        ImageReader reader = new ImageReader();
        try {
            return reader.isThisType(file.getPath(), true);
        } catch (Throwable ignored) {
            return false;
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
                // Nothing was opened or Bio-Formats already released it.
            }
        }
    }

    private String relativePath(File file) {
        return new File(selectedFolder).toPath().relativize(file.toPath()).toString()
            .replace(File.separatorChar, '/');
    }

    private void loadSelectedImage() {
        if (selectedFolder == null || imageList.getSelectedItem() == null) {
            IJ.error("Measure line", "Choose a folder and image file first.");
            return;
        }
        File source = new File(selectedFolder,
            imageList.getSelectedItem().replace('/', File.separatorChar));
        ImagePlus before = WindowManager.getCurrentImage();
        if (new Opener().getFileType(source.getPath()) == Opener.UNKNOWN) {
            String optionPath = source.getPath().replace("\\", "\\\\").replace("]", "\\]");
            IJ.run("Bio-Formats Importer", "open=[" + optionPath + "]");
        } else {
            IJ.open(source.getPath());
        }
        ImagePlus opened = WindowManager.getCurrentImage();
        if (opened == null || opened == before) {
            IJ.error("Measure line", "Could not open the selected image.");
            return;
        }
        attachToImage(opened);
        activeSourceFile = source;
        setStatus("Active: " + opened.getTitle());
    }

    private void loadNextImage() {
        int next = imageList.getSelectedIndex() + 1;
        if (next >= imageList.getItemCount()) {
            setStatus(imageList.getItemCount() == 0 ? "No images to load" : "No next image");
            return;
        }
        imageList.select(next);
        loadSelectedImage();
    }

    private void closeCurrentImage() {
        ImagePlus current = WindowManager.getCurrentImage();
        if (current == null) {
            setStatus("No current image to close");
            return;
        }
        current.close();
    }

    private void saveImage() {
        if (!ensureImage()) {
            return;
        }
        File source = activeSourceFile != null ? activeSourceFile : sourceFile(image);
        File directory = source != null ? source.getParentFile()
            : selectedFolder == null ? null : new File(selectedFolder);
        if (directory == null) {
            IJ.error("Measure line", "Choose an image folder before saving.");
            return;
        }
        String sourceName = source == null ? image.getTitle() : source.getName();
        String sourceExtension = extensionOf(sourceName);
        String outputExtension = outputExtension(sourceExtension);
        String base = sourceExtension.length() == 0 ? sourceName
            : sourceName.substring(0, sourceName.length() - sourceExtension.length());
        String suffix = suffixField.getText().trim();
        File target = new File(directory, base + suffix + outputExtension);
        if (suffix.length() == 0) {
            String title;
            String message;
            if (source != null && sameFile(source, target)) {
                title = "Overwrite original?";
                message = "Save without a suffix and overwrite:\n" + source.getPath();
            } else {
                title = "Save converted image?";
                message = "The source format cannot be overwritten. Save as:\n" + target.getPath();
            }
            if (!IJ.showMessageWithCancel(title, message)) {
                return;
            }
        }
        if (saveByExtension(target, outputExtension)) {
            image.changes = false;
            setStatus("Saved: " + target.getName());
        } else {
            IJ.error("Measure line", "Could not save " + target.getName());
        }
    }

    private String outputExtension(String sourceExtension) {
        if (sourceExtension.equals(".tif") || sourceExtension.equals(".tiff")) {
            return sourceExtension;
        }
        if (image.getStackSize() == 1 && Arrays.asList(
                ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".pgm").contains(sourceExtension)) {
            return sourceExtension;
        }
        return ".tif";
    }

    private boolean saveByExtension(File target, String extension) {
        FileSaver saver = new FileSaver(image);
        if (extension.equals(".tif") || extension.equals(".tiff")) {
            return image.getStackSize() > 1
                ? saver.saveAsTiffStack(target.getPath()) : saver.saveAsTiff(target.getPath());
        }
        if (extension.equals(".png")) return saver.saveAsPng(target.getPath());
        if (extension.equals(".jpg") || extension.equals(".jpeg")) return saver.saveAsJpeg(target.getPath());
        if (extension.equals(".gif")) return saver.saveAsGif(target.getPath());
        if (extension.equals(".bmp")) return saver.saveAsBmp(target.getPath());
        if (extension.equals(".pgm")) return saver.saveAsPgm(target.getPath());
        return false;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase();
    }

    private static String fileStem(File file) {
        String name = file.getName();
        String extension = extensionOf(name);
        return extension.length() == 0 ? name : name.substring(0, name.length() - extension.length());
    }

    private static String stemKey(File file, String stem) {
        return new File(file.getParentFile(), stem).getAbsolutePath();
    }

    private static boolean sameFile(File first, File second) {
        try {
            return first.getCanonicalFile().equals(second.getCanonicalFile());
        } catch (Exception ignored) {
            return first.getAbsoluteFile().equals(second.getAbsoluteFile());
        }
    }

    private static File sourceFile(ImagePlus imp) {
        FileInfo info = imp == null ? null : imp.getOriginalFileInfo();
        return info == null || info.directory == null || info.fileName == null
            ? null : new File(info.directory, info.fileName);
    }

    private boolean applySettings() {
        int selectedSpecies = animalChoice.getSelectedIndex();
        int selectedDivisions = numberOfDivisions;
        double[] selectedTargets = targetFrequencies;
        double selectedScale = Double.NaN;
        String selectedUnit = "";

        try {
            if (selectedSpecies == EQUAL_DIVISIONS) {
                try {
                    selectedDivisions = Integer.parseInt(divisionField.getText().trim());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Equal divisions must be a whole number.");
                }
                if (selectedDivisions < 2 || selectedDivisions > 500) {
                    throw new IllegalArgumentException("Equal divisions must be between 2 and 500.");
                }
            } else {
                selectedTargets = parseTargetFrequencies(targetField.getText());
            }
            String scaleText = scaleField.getText().trim();
            selectedUnit = unitField.getText().trim();
            if (scaleText.length() > 0) {
                try {
                    selectedScale = Double.parseDouble(scaleText);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Scale per pixel must be a positive number.");
                }
                if (!(selectedScale > 0) || Double.isInfinite(selectedScale)
                        || Double.isNaN(selectedScale)) {
                    throw new IllegalArgumentException("Scale per pixel must be a positive number.");
                }
                if (selectedUnit.length() == 0) {
                    throw new IllegalArgumentException("Enter a unit for the custom scale.");
                }
            } else if (selectedUnit.length() > 0) {
                throw new IllegalArgumentException("Enter a scale per pixel or leave both scale fields blank.");
            }
        } catch (IllegalArgumentException exception) {
            IJ.error("Measure line", exception.getMessage());
            return false;
        }

        if (species != selectedSpecies
                || numberOfDivisions != selectedDivisions
                || !Arrays.equals(targetFrequencies, selectedTargets)
                || Double.compare(customScale, selectedScale) != 0
                || !customUnit.equals(selectedUnit)) {
            calculated = false;
        }
        species = selectedSpecies;
        numberOfDivisions = selectedDivisions;
        targetFrequencies = selectedTargets;
        customScale = selectedScale;
        customUnit = selectedUnit;
        updateScaleDisplay();
        return true;
    }

    static double[] parseTargetFrequencies(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter at least one target frequency.");
        }
        String[] values = text.trim().split("[,;\\s]+");
        List<Double> parsed = new ArrayList<Double>();
        for (String value : values) {
            double frequency;
            try {
                frequency = Double.parseDouble(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Target frequencies must be numbers separated by commas.");
            }
            if (!(frequency > 0) || Double.isInfinite(frequency) || Double.isNaN(frequency)) {
                throw new IllegalArgumentException("Target frequencies must be positive numbers.");
            }
            if (!parsed.contains(frequency)) {
                parsed.add(frequency);
            }
        }
        double[] result = new double[parsed.size()];
        for (int i = 0; i < parsed.size(); i++) {
            result[i] = parsed.get(i);
        }
        return result;
    }

    private void resetTraceData() {
        Arrays.fill(pointCounts, 0);
        Arrays.fill(xCoords, null);
        Arrays.fill(yCoords, null);
        Arrays.fill(pixelLengths, null);
        Arrays.fill(frequencies, null);
        Arrays.fill(bounds, null);
        numPieces = 0;
        currentPiece = 0;
        totalPixelLength = -1;
        calculated = false;
        picking = false;
        resetUndoState();
        if (pointButton != null) {
            pointButton.setLabel("Toggle point mode");
        }
    }

    private void resetUndoState() {
        undoActions.clear();
        clearAnnotationState();
    }

    private void clearAnnotationState() {
        annotationVisible = false;
        pointAnnotations.clear();
        pointBaseProcessor = null;
    }

    private void discardVisibleAnnotations() {
        while (!undoActions.isEmpty()
                && undoActions.get(undoActions.size() - 1) != UNDO_SEGMENT) {
            undoActions.remove(undoActions.size() - 1);
        }
        clearAnnotationState();
    }

    private void undoLastAction() {
        if (!ensureImage()) {
            return;
        }
        if (undoActions.isEmpty()) {
            setStatus("Nothing to undo");
            return;
        }
        int action = undoActions.remove(undoActions.size() - 1);
        if (action == UNDO_POINT) {
            if (!pointAnnotations.isEmpty()) {
                pointAnnotations.remove(pointAnnotations.size() - 1);
            }
            redrawPointAnnotations();
            setStatus("Undid point annotation");
        } else if (action == UNDO_ANNOTATION) {
            clearAnnotationState();
            restoreTraceImage();
            setStatus("Undid annotation");
        } else if (action == UNDO_SEGMENT) {
            removeLastSegment(false);
        }
    }

    private void redrawPointAnnotations() {
        if (pointBaseProcessor == null) {
            return;
        }
        processor.copyBits(pointBaseProcessor, 0, 0, Blitter.COPY);
        for (PointAnnotation annotation : pointAnnotations) {
            drawPointAnnotation(annotation);
        }
        image.updateAndDraw();
        if (pointAnnotations.isEmpty() && !annotationVisible) {
            pointBaseProcessor = null;
        }
    }

    private void cancelCurrentSegmentation() {
        if (image == null || image.getWindow() == null) {
            return;
        }
        Roi roi = image.getRoi();
        if (roi instanceof PolygonRoi && roi.getType() == Roi.POLYLINE) {
            image.deleteRoi();
            setStatus("Current segment cancelled");
            selectSegmentedLine();
        }
    }

    private boolean ensureImage() {
        if (image != null && image.getWindow() != null && processor != null) {
            return true;
        }
        ImagePlus current = WindowManager.getCurrentImage();
        if (current != null && attachToImage(current)) {
            return true;
        }
        IJ.error("Measure line", "Open an image first.");
        return false;
    }

    private void clearAnnotations() {
        if (!ensureImage()) {
            return;
        }
        resetUndoState();
        restoreTraceImage();
        setStatus("Annotations cleared");
        selectSegmentedLine();
    }

    private void clearLastSegment() {
        removeLastSegment(true);
    }

    private void removeLastSegment(boolean resetHistory) {
        if (!ensureImage()) {
            return;
        }
        if (numPieces == 0) {
            setStatus("No segment to clear");
            return;
        }
        if (resetHistory) {
            resetUndoState();
        } else {
            clearAnnotationState();
        }
        int removed = numPieces - 1;
        pointCounts[removed] = 0;
        xCoords[removed] = null;
        yCoords[removed] = null;
        pixelLengths[removed] = null;
        frequencies[removed] = null;
        bounds[removed] = null;
        numPieces--;
        currentPiece = numPieces;
        calculated = false;
        picking = false;
        image.deleteRoi();
        redrawStoredTrace();
        if (pointButton != null) {
            pointButton.setLabel("Toggle point mode");
        }
        setStatus(resetHistory ? "Cleared last segment" : "Undid last segment");
        selectSegmentedLine();
    }

    private void redrawStoredTrace() {
        processor.copyBits(cleanProcessor, 0, 0, Blitter.COPY);
        image.setColor(Color.yellow);
        processor.setLineWidth(fixedLineWidth);
        processor.setFont(labelFont);
        for (int piece = 0; piece < numPieces; piece++) {
            for (int point = 1; point < pointCounts[piece]; point++) {
                processor.drawLine((int) xCoords[piece][point - 1], (int) yCoords[piece][point - 1],
                    (int) xCoords[piece][point], (int) yCoords[piece][point]);
            }
            int last = pointCounts[piece] - 1;
            int middleX = (int) ((xCoords[piece][0] + xCoords[piece][last]) / 2);
            int middleY = (int) ((yCoords[piece][0] + yCoords[piece][last]) / 2);
            processor.drawString("Piece " + (piece + 1), middleX, middleY);
        }
        traceProcessor = processor.duplicate();
        image.updateAndDraw();
        Roi.setColor(Color.yellow);
    }

    private void clearAll() {
        if (!ensureImage()) {
            return;
        }
        processor.copyBits(cleanProcessor, 0, 0, Blitter.COPY);
        traceProcessor = cleanProcessor.duplicate();
        image.deleteRoi();
        image.updateAndDraw();
        resetTraceData();
        setStatus("All cleared");
        selectSegmentedLine();
    }

    private void restoreTraceImage() {
        if (traceProcessor != null) {
            processor.copyBits(traceProcessor, 0, 0, Blitter.COPY);
            image.updateAndDraw();
        }
    }

    private void storeCurrentLine() {
        if (!ensureImage()) {
            return;
        }
        Roi roi = image.getRoi();
        if (!(roi instanceof PolygonRoi) || roi.getType() != Roi.POLYLINE) {
            IJ.error("Measure line", "Draw a segmented line first.");
            return;
        }
        if (currentPiece >= MAX_PIECES) {
            IJ.error("Measure line", "The maximum number of pieces is " + MAX_PIECES + ".");
            return;
        }

        PolygonRoi line = (PolygonRoi) roi;
        calibration = image.getCalibration();
        double pixelWidth = calibration.scaled() ? calibration.pixelWidth : 1.0;
        int requestedPoints = Math.max(2, (int) Math.round(line.getLength() / pixelWidth));
        line.fitSpline(requestedPoints);
        int count = line.getNCoordinates();
        if (count < 2) {
            IJ.error("Measure line", "The segmented line is too short.");
            return;
        }

        Rectangle lineBounds = line.getBounds();
        int[] localX = line.getXCoordinates();
        int[] localY = line.getYCoordinates();
        xCoords[currentPiece] = new double[count];
        yCoords[currentPiece] = new double[count];
        for (int i = 0; i < count; i++) {
            xCoords[currentPiece][i] = lineBounds.x + localX[i];
            yCoords[currentPiece][i] = lineBounds.y + localY[i];
        }
        pointCounts[currentPiece] = count;
        bounds[currentPiece] = lineBounds;
        numPieces = currentPiece + 1;

        discardVisibleAnnotations();
        restoreTraceImage();
        image.setColor(Color.yellow);
        processor.setLineWidth(fixedLineWidth);
        line.drawPixels(processor);
        processor.setFont(labelFont);
        int middleX = (int) ((xCoords[currentPiece][0] + xCoords[currentPiece][count - 1]) / 2);
        int middleY = (int) ((yCoords[currentPiece][0] + yCoords[currentPiece][count - 1]) / 2);
        processor.drawString("Piece " + (currentPiece + 1), middleX, middleY);
        image.updateAndDraw();
        traceProcessor = processor.duplicate();
        Roi.setColor(Color.yellow);

        currentPiece++;
        undoActions.add(UNDO_SEGMENT);
        calculated = false;
        picking = false;
        image.deleteRoi();
        setStatus("Stored piece " + currentPiece);
        selectSegmentedLine();
    }

    private boolean calculate() {
        if (!ensureImage()) {
            return false;
        }
        if (!applySettings()) {
            return false;
        }
        if (calculated) {
            return true;
        }
        if (numPieces == 0) {
            IJ.error("Measure line", "Store at least one segment first.");
            return false;
        }

        double runningLength = 0;
        for (int piece = 0; piece < numPieces; piece++) {
            int count = pointCounts[piece];
            pixelLengths[piece] = new double[count];
            for (int point = 0; point < count; point++) {
                if (point > 0) {
                    runningLength += distance(
                        xCoords[piece][point - 1], yCoords[piece][point - 1],
                        xCoords[piece][point], yCoords[piece][point]);
                }
                pixelLengths[piece][point] = runningLength;
            }
        }
        totalPixelLength = runningLength;
        if (!(totalPixelLength > 0)) {
            IJ.error("Measure line", "The stored line has no measurable length.");
            return false;
        }

        for (int piece = 0; piece < numPieces; piece++) {
            frequencies[piece] = new double[pointCounts[piece]];
            for (int point = 0; point < pointCounts[piece]; point++) {
                double fraction = pixelLengths[piece][point] / totalPixelLength;
                frequencies[piece][point] = frequencyAt(fraction);
            }
        }

        if (species == EQUAL_DIVISIONS) {
            calculateDivisionPoints();
        } else {
            calculateTargetPoints();
        }
        calculated = true;
        return true;
    }

    private void calculateTargetPoints() {
        targetPoints = new double[targetFrequencies.length][2];
        for (double[] point : targetPoints) {
            Arrays.fill(point, -1);
        }

        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int piece = 0; piece < numPieces; piece++) {
            for (double frequency : frequencies[piece]) {
                minimum = Math.min(minimum, frequency);
                maximum = Math.max(maximum, frequency);
            }
        }

        for (int target = 0; target < targetFrequencies.length; target++) {
            double wanted = targetFrequencies[target];
            if (wanted < minimum || wanted > maximum) {
                continue;
            }
            double bestDifference = Double.POSITIVE_INFINITY;
            for (int piece = 0; piece < numPieces; piece++) {
                for (int point = 0; point < pointCounts[piece]; point++) {
                    double difference = Math.abs(frequencies[piece][point] - wanted);
                    if (difference < bestDifference) {
                        bestDifference = difference;
                        targetPoints[target][0] = xCoords[piece][point];
                        targetPoints[target][1] = yCoords[piece][point];
                    }
                }
            }
        }
    }

    private void calculateDivisionPoints() {
        divisionPoints = new double[numberOfDivisions][4];
        for (int division = 0; division < numberOfDivisions; division++) {
            double fraction = (division + 1.0) / numberOfDivisions;
            int bestPiece = 0;
            int bestPoint = 0;
            double bestDifference = Double.POSITIVE_INFINITY;
            for (int piece = 0; piece < numPieces; piece++) {
                for (int point = 0; point < pointCounts[piece]; point++) {
                    double difference = Math.abs(frequencies[piece][point] - fraction);
                    if (difference < bestDifference) {
                        bestDifference = difference;
                        bestPiece = piece;
                        bestPoint = point;
                    }
                }
            }
            int before = Math.max(0, bestPoint - 5);
            int after = Math.min(pointCounts[bestPiece] - 1, bestPoint + 5);
            divisionPoints[division][0] = xCoords[bestPiece][bestPoint];
            divisionPoints[division][1] = yCoords[bestPiece][bestPoint];
            divisionPoints[division][2] = xCoords[bestPiece][after] - xCoords[bestPiece][before];
            divisionPoints[division][3] = yCoords[bestPiece][after] - yCoords[bestPiece][before];
        }
    }

    private double frequencyAt(double fraction) {
        switch (species) {
            case CAT:
                return (Math.pow(10, (100 - fraction * 100) * 0.021) - 0.8) * 0.456;
            case GUINEA_PIG:
                return Math.pow(10, (66.4 - fraction * 100) / 38.2);
            case CHINCHILLA:
                return Math.exp((100 - fraction * 100) * 0.051) * 0.125;
            case HUMAN:
                return (Math.pow(10, (1 - fraction) * 2) - 0.4) * 0.2;
            case MOUSE:
                return (Math.pow(10, (1 - fraction) * 0.92) - 0.68) * 9.8;
            case RAT:
                return -Math.log((fraction * 100 + 4.632) / 102.048) / 0.04357;
            case RHESUS_MONKEY:
                return (Math.pow(10, (1 - fraction) * 2.1) - 0.85) * 0.36;
            case GERBIL:
                return 0.398 * (Math.pow(10, (1 - fraction) * 2.2) - 0.631);
            case MARMOSET:
                return 0.2557 * (Math.pow(10, (1 - fraction) * 2.1) - 0.686);
            default:
                return fraction;
        }
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }

    private void annotate() {
        if (!calculate()) {
            return;
        }
        discardVisibleAnnotations();
        restoreTraceImage();
        if (species == EQUAL_DIVISIONS) {
            drawDivisions();
        } else {
            drawFrequencyTargets();
        }
        if (addLengthCheckbox.getState()) {
            drawTotalLengthLabel();
        }
        image.updateAndDraw();
        annotationVisible = true;
        pointBaseProcessor = processor.duplicate();
        undoActions.add(UNDO_ANNOTATION);
        setStatus("Annotated");
        canvas.requestFocus();
    }

    private void drawFrequencyTargets() {
        image.setColor(Toolbar.getForegroundColor());
        processor.setLineWidth(dynamicLineWidth);
        processor.setFont(labelFont);
        for (int i = 0; i < targetFrequencies.length; i++) {
            if (targetPoints[i][0] < 0) {
                continue;
            }
            int x = (int) targetPoints[i][0];
            int y = (int) targetPoints[i][1];
            processor.drawDot(x, y);
            processor.drawString(formatNumber(targetFrequencies[i]), x, y);
        }
    }

    private void drawDivisions() {
        image.setColor(Toolbar.getForegroundColor());
        processor.setLineWidth(fixedLineWidth);
        processor.setFont(labelFont);
        for (int i = 0; i < numberOfDivisions; i++) {
            double x = divisionPoints[i][0];
            double y = divisionPoints[i][1];
            double dx = divisionPoints[i][2];
            double dy = divisionPoints[i][3];
            double length = Math.hypot(dx, dy);
            if (!(length > 0)) {
                continue;
            }
            double normalX = dy / length;
            double normalY = -dx / length;
            int x1 = (int) (x + normalX * divisionLineLength);
            int y1 = (int) (y + normalY * divisionLineLength);
            int x2 = (int) (x - normalX * divisionLineLength);
            int y2 = (int) (y - normalY * divisionLineLength);
            processor.drawLine(x1, y1, x2, y2);
            processor.drawString(Integer.toString(numberOfDivisions - i),
                (int) (x + normalX * divisionLineLength * 1.2),
                (int) (y + normalY * divisionLineLength * 1.2));
        }
    }

    private void showMeasurements() {
        if (!calculate()) {
            return;
        }
        calibration = image.getCalibration();
        String unit = lengthUnit();
        StringBuilder rows = new StringBuilder();
        for (int piece = 0; piece < numPieces; piece++) {
            double length = displayLength(pixelLengths[piece][pointCounts[piece] - 1]
                - pixelLengths[piece][0]);
            rows.append(piece + 1).append('\t').append(roundFour(length)).append('\n');
        }
        double total = displayLength(totalPixelLength);
        rows.append("Whole\t").append(roundFour(total));
        new TextWindow("Measurements", "piece\tlength (" + unit + ")", rows.toString(), 300, 300);
        setStatus("Measurements shown");
    }

    private String lengthUnit() {
        if (!Double.isNaN(customScale)) {
            return customUnit;
        }
        Calibration currentCalibration = image.getCalibration();
        return currentCalibration.scaled() ? currentCalibration.getUnit() : "pixels";
    }

    private double displayLength(double pixelLength) {
        if (!Double.isNaN(customScale)) {
            return pixelLength * customScale;
        }
        Calibration currentCalibration = image.getCalibration();
        return currentCalibration.scaled() ? pixelLength * currentCalibration.pixelWidth : pixelLength;
    }

    private void updateScaleDisplay() {
        if (scaleDisplayLabel == null) {
            return;
        }
        String scaleText = scaleField.getText().trim();
        String unitText = unitField.getText().trim();
        if (scaleText.length() > 0 && unitText.length() > 0) {
            try {
                double scale = Double.parseDouble(scaleText);
                if (scale > 0 && !Double.isInfinite(scale) && !Double.isNaN(scale)) {
                    scaleDisplayLabel.setText(formatScale(scale) + " " + unitText + "/pixel");
                    return;
                }
            } catch (NumberFormatException ignored) {
                // Invalid custom values are reported when an action applies the settings.
            }
        }
        if (image == null) {
            scaleDisplayLabel.setText("No image scale");
            return;
        }
        Calibration nativeCalibration = image.getCalibration();
        if (nativeCalibration.scaled()) {
            scaleDisplayLabel.setText(formatScale(nativeCalibration.pixelWidth) + " "
                + nativeCalibration.getUnit() + "/pixel");
        } else {
            scaleDisplayLabel.setText("1 pixel/pixel");
        }
    }

    private static String formatScale(double value) {
        String text = IJ.d2s(value, 4);
        while (text.indexOf('.') >= 0 && text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    private void drawTotalLengthLabel() {
        String label = totalLengthText();
        image.setColor(Toolbar.getForegroundColor());
        processor.setFont(labelFont);
        int x = Math.max(2, image.getWidth() - processor.getStringWidth(label) - 5);
        int y = Math.max(labelFont.getSize(), image.getHeight() - 5);
        processor.drawString(label, x, y);
    }

    private String totalLengthText() {
        return "Cochlea length: " + IJ.d2s(displayLength(totalPixelLength), 1)
            + " " + lengthUnit();
    }

    private static double roundFour(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value);
    }

    private void togglePointMode() {
        if (!calculate()) {
            return;
        }
        picking = !picking;
        pointButton.setLabel(picking ? "Toggle point mode: on" : "Toggle point mode");
        setStatus(picking ? "Point mode on" : "Point mode off");
        canvas.requestFocus();
    }

    private void setStatus(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
    }

    private void removePlugin() {
        ImagePlus.removeImageListener(this);
        detachCanvas();
        resetUndoState();
        if (controls != null) {
            controls.dispose();
            controls = null;
        }
        image = null;
        imageWindow = null;
        processor = null;
        cleanProcessor = null;
        traceProcessor = null;
        activeSourceFile = null;
        synchronized (Measure_line.class) {
            if (activeInstance == this) {
                activeInstance = null;
            }
        }
    }

    @Override
    public void imageOpened(ImagePlus openedImage) {
        if (activeInstance == this && attachToImage(openedImage)) {
            openedImage.getWindow().toFront();
            openedImage.getCanvas().requestFocus();
            setStatus("Active: " + openedImage.getTitle());
        }
    }

    @Override
    public void imageClosed(ImagePlus closedImage) {
        if (closedImage != image) {
            return;
        }
        detachCanvas();
        image = null;
        imageWindow = null;
        processor = null;
        cleanProcessor = null;
        traceProcessor = null;
        activeSourceFile = null;
        resetUndoState();
        updateScaleDisplay();
        ImagePlus current = WindowManager.getCurrentImage();
        if (current != null && attachToImage(current)) {
            setStatus("Active: " + current.getTitle());
        } else {
            setStatus("Waiting for the next image");
            if (controls != null) {
                controls.requestFocus();
            }
        }
    }

    @Override
    public void imageUpdated(ImagePlus updatedImage) {
        if (activeInstance != this || updatedImage == image || updatedImage.getWindow() == null) {
            return;
        }
        attachToImage(updatedImage);
    }

    @Override
    public void keyTyped(KeyEvent event) {
        switch (Character.toLowerCase(event.getKeyChar())) {
            case 'a':
                annotate();
                break;
            case 'm':
                showMeasurements();
                break;
            case 'p':
                togglePointMode();
                break;
            case 's':
                saveImage();
                break;
            case 'n':
                loadNextImage();
                break;
            case 'c':
                closeCurrentImage();
                break;
            default:
                break;
        }
    }

    @Override
    public void keyPressed(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
            cancelCurrentSegmentation();
            event.consume();
        } else if (event.getKeyCode() == KeyEvent.VK_Z
                && (event.isControlDown() || event.isMetaDown())) {
            undoLastAction();
            event.consume();
        }
    }
    @Override public void keyReleased(KeyEvent event) { }

    @Override
    public void mouseClicked(MouseEvent event) {
        if (!picking && event.getClickCount() >= 2) {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    if (image != null && image.getRoi() instanceof PolygonRoi
                            && image.getRoi().getType() == Roi.POLYLINE) {
                        storeCurrentLine();
                    }
                }
            });
            return;
        }
        if (!picking || !calculated) {
            return;
        }
        int mouseX = canvas.offScreenX(event.getX());
        int mouseY = canvas.offScreenY(event.getY());
        int[] nearest = nearestStoredPoint(mouseX, mouseY, 7);
        if (nearest == null) {
            return;
        }
        int piece = nearest[0];
        int point = nearest[1];
        int x = (int) xCoords[piece][point];
        int y = (int) yCoords[piece][point];
        if (pointAnnotations.isEmpty()) {
            pointBaseProcessor = processor.duplicate();
        }
        PointAnnotation annotation = new PointAnnotation(
            x, y, formatNumber(roundTwo(frequencies[piece][point])));
        pointAnnotations.add(annotation);
        undoActions.add(UNDO_POINT);
        drawPointAnnotation(annotation);
        image.updateAndDraw();
    }

    private void drawPointAnnotation(PointAnnotation annotation) {
        image.setColor(Toolbar.getForegroundColor());
        processor.setLineWidth(dynamicLineWidth);
        processor.drawDot(annotation.x, annotation.y);
        processor.setFont(labelFont);
        processor.drawString(annotation.label, annotation.x, annotation.y);
    }

    @Override
    public void mouseMoved(MouseEvent event) {
        if (!picking || !calculated) {
            return;
        }
        int[] nearest = nearestStoredPoint(
            canvas.offScreenX(event.getX()), canvas.offScreenY(event.getY()), 7);
        if (nearest == null) {
            IJ.showStatus("");
            return;
        }
        int piece = nearest[0];
        int point = nearest[1];
        double percentage = roundTwo(100 * pixelLengths[piece][point] / totalPixelLength);
        if (species == EQUAL_DIVISIONS) {
            IJ.showStatus("Piece: " + (piece + 1) + "     Length percentage: " + percentage);
        } else {
            IJ.showStatus("Piece: " + (piece + 1) + "     Frequency: "
                + roundTwo(frequencies[piece][point]) + " kHz     Length percentage: " + percentage);
        }
    }

    private int[] nearestStoredPoint(int mouseX, int mouseY, int radius) {
        double best = radius * radius;
        int[] result = null;
        for (int piece = 0; piece < numPieces; piece++) {
            if (bounds[piece] == null) {
                continue;
            }
            Rectangle expanded = new Rectangle(bounds[piece]);
            expanded.grow(radius, radius);
            if (!expanded.contains(mouseX, mouseY)) {
                continue;
            }
            for (int point = 0; point < pointCounts[piece]; point++) {
                double dx = xCoords[piece][point] - mouseX;
                double dy = yCoords[piece][point] - mouseY;
                double squared = dx * dx + dy * dy;
                if (squared <= best) {
                    best = squared;
                    result = new int[] {piece, point};
                }
            }
        }
        return result;
    }

    private static double roundTwo(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static class PointAnnotation {
        final int x;
        final int y;
        final String label;

        PointAnnotation(int x, int y, String label) {
            this.x = x;
            this.y = y;
            this.label = label;
        }
    }

    @Override public void mousePressed(MouseEvent event) { }
    @Override public void mouseReleased(MouseEvent event) { }
    @Override public void mouseEntered(MouseEvent event) { }
    @Override public void mouseExited(MouseEvent event) { }
    @Override public void mouseDragged(MouseEvent event) { }
}
