/*
 * Automatic batch organization and stitching of grayscale TIFF tiles.
 *
 * Run with Plugins > Macros > Run and select the folder containing the tiles.
 * Disconnected stitched components are placed left-to-right in filename order.
 */

argument = getArgument();
root = argument;
minimumCorrelation = 0.70;
overwriteExisting = true;
pseudocolor = "Original";
if (root == "") {
    root = getDirectory("Choose the folder containing the TIFF tiles");
    Dialog.create("Batch stitching settings");
    Dialog.addNumber("Minimum overlap correlation:", minimumCorrelation);
    Dialog.addCheckbox("Overwrite existing results", overwriteExisting);
    Dialog.addChoice("Set output image pseudocolor:",
        newArray("Original", "Red", "Green", "Cyan", "Yellow", "Magenta", "Blue"),
        pseudocolor);
    Dialog.addMessage("Decrease the threshold if genuine overlapping images are not stitched.\n" +
        "Increase it if non-overlapping images are mistakenly overlaid.");
    Dialog.show();
    minimumCorrelation = Dialog.getNumber();
    overwriteExisting = Dialog.getCheckbox();
    pseudocolor = Dialog.getChoice();
}
if (isNaN(minimumCorrelation) || minimumCorrelation < 0 || minimumCorrelation > 1)
    exit("The overlap correlation threshold must be between 0 and 1.");
root = replace(root, "\\", "/");
if (!endsWith(root, "/"))
    root = root + "/";

preflightTree(root);
processTree(root, minimumCorrelation, overwriteExisting, pseudocolor);

showStatus("Batch stitching finished.");
print("Automatic batch stitching finished.");


function preflightMoves(root) {
    files = getFileList(root);
    conflicts = "";
    rootGroup = folderName(root);

    for (i = 0; i < files.length; i++) {
        name = files[i];
        if (endsWith(name, "/") || !isTile(name))
            continue;

        group = tileBase(name);
        if (group == rootGroup)
            continue;
        groupDir = root + group + "/";
        if ((File.exists(groupDir) && !File.isDirectory(groupDir)) ||
            File.exists(groupDir + name))
            conflicts = conflicts + "\n" + name;
    }

    if (conflicts != "")
        exit("Nothing was moved because these destination files already exist:" + conflicts);
}


function preflightTree(dir) {
    preflightMoves(dir);
    entries = getFileList(dir);
    Array.sort(entries);

    for (i = 0; i < entries.length; i++) {
        entry = entries[i];
        if (endsWith(entry, "/") && entry != ".stitch-input/")
            preflightTree(dir + entry);
    }
}


function organizeTiles(root) {
    files = getFileList(root);
    rootGroup = folderName(root);

    for (i = 0; i < files.length; i++) {
        name = files[i];
        if (endsWith(name, "/") || !isTile(name))
            continue;
        group = tileBase(name);
        if (group == rootGroup)
            continue;

        groupDir = root + group + "/";
        File.makeDirectory(groupDir);
        if (!File.isDirectory(groupDir))
            exit("Could not create folder:\n" + groupDir);
        if (!File.rename(root + name, groupDir + name))
            exit("Could not move:\n" + root + name + "\n\nTo:\n" + groupDir + name);
    }
}


function processTree(dir, minimumCorrelation, overwriteExisting, pseudocolor) {
    organizeTiles(dir);
    group = folderName(dir);
    if (containsGroupTiles(dir, group))
        processGroup(dir, group, minimumCorrelation, overwriteExisting, pseudocolor);

    entries = getFileList(dir);
    Array.sort(entries);

    for (i = 0; i < entries.length; i++) {
        entry = entries[i];
        if (!endsWith(entry, "/") || entry == ".stitch-input/")
            continue;
        processTree(dir + entry, minimumCorrelation, overwriteExisting, pseudocolor);
    }
}


function processGroup(groupDir, group, minimumCorrelation, overwriteExisting, pseudocolor) {
    output = groupDir + group + ".tif";
    if (File.exists(output)) {
        if (!overwriteExisting) {
            print("Skipping " + group + ": output already exists.");
            return;
        }
        if (!File.delete(output)) {
            print("Skipping " + group + ": existing output could not be overwritten.");
            return;
        }
    }

    beforeCount = nImages;
    result = -1;
    count = countGroupTiles(groupDir, group);
    showStatus("Processing " + group + "...");

    if (count == 1) {
        open(groupDir + firstGroupTile(groupDir, group));
        result = getImageID();
    } else {
        stitchDir = prepareStitchDirectory(groupDir, group);
        oldLog = getInfo("log");
        // Omitted checkbox options, including Confirm files, are intentionally unchecked.
        options = "type=[Unknown position] order=[All files in directory]" +
            " directory=[" + stitchDir + "]" +
            " output_textfile_name=TileConfiguration.txt" +
            " fusion_method=[Linear Blending]" +
            " regression_threshold=" + minimumCorrelation +
            " max/avg_displacement_threshold=2.50" +
            " absolute_displacement_threshold=3.50" +
            " subpixel_accuracy" +
            " computation_parameters=[Save computation time (but use more RAM)]" +
            " image_output=[Fuse and display]";
        run("Grid/Collection stitching", options);
        if (nImages > beforeCount)
            result = getImageID();

        newLog = getInfo("log");
        if (startsWith(newLog, oldLog))
            newLog = substring(newLog, lengthOf(oldLog));

        components = writeAutomaticLayout(stitchDir, group, newLog, minimumCorrelation);
        if (components > 1) {
            print(group + ": placing " + components + " disconnected components left-to-right.");
            if (result != -1) {
                selectImage(result);
                setOption("Changes", false);
                close();
                result = -1;
            }
            options = "type=[Positions from file] order=[Defined by TileConfiguration]" +
                " directory=[" + stitchDir + "]" +
                " layout_file=TileConfiguration.automatic.txt" +
                " fusion_method=[Linear Blending]" +
                " regression_threshold=" + minimumCorrelation +
                " max/avg_displacement_threshold=2.50" +
                " absolute_displacement_threshold=3.50" +
                " subpixel_accuracy" +
                " computation_parameters=[Save computation time (but use more RAM)]" +
                " image_output=[Fuse and display]";
            run("Grid/Collection stitching", options);
            if (nImages > beforeCount)
                result = getImageID();
        } else if (components < 0) {
            print(group + ": could not inspect disconnected components; keeping Fiji's fused result.");
        }
        cleanupStitchDirectory(stitchDir);
    }

    if (result == -1) {
        print("Skipping " + group + ": no image is available to save.");
        return;
    }

    selectImage(result);
    if (pseudocolor != "Original") {
        run(pseudocolor);
        run("RGB Color");
    }
    rename(group);
    saveAs("Tiff", output);
    print("Saved " + output);
    setOption("Changes", false);
    close();
}


function isTile(name) {
    lower = toLowerCase(name);
    if (!endsWith(lower, ".tif") && !endsWith(lower, ".tiff"))
        return false;
    stem = File.getNameWithoutExtension(name);
    return matches(stem, ".+[-_][0-9]{4}$");
}


function tileBase(name) {
    stem = File.getNameWithoutExtension(name);
    return substring(stem, 0, lengthOf(stem) - 5);
}


function folderName(dir) {
    if (endsWith(dir, "/"))
        dir = substring(dir, 0, lengthOf(dir) - 1);
    return File.getName(dir);
}


function containsGroupTiles(dir, group) {
    files = getFileList(dir);
    for (i = 0; i < files.length; i++) {
        name = files[i];
        if (!endsWith(name, "/") && isTile(name)) {
            base = tileBase(name);
            if (base == group)
                return true;
        }
    }
    return false;
}


function countGroupTiles(dir, group) {
    files = getFileList(dir);
    count = 0;
    for (i = 0; i < files.length; i++) {
        name = files[i];
        if (!endsWith(name, "/") && isTile(name)) {
            base = tileBase(name);
            if (base == group)
                count++;
        }
    }
    return count;
}


function firstGroupTile(dir, group) {
    files = getFileList(dir);
    Array.sort(files);
    for (i = 0; i < files.length; i++) {
        name = files[i];
        if (!endsWith(name, "/") && isTile(name)) {
            base = tileBase(name);
            if (base == group)
                return name;
        }
    }
    return "";
}


function prepareStitchDirectory(groupDir, group) {
    dir = groupDir + ".stitch-input/";
    cleanupStitchDirectory(dir);
    File.makeDirectory(dir);
    if (!File.isDirectory(dir))
        exit("Could not create temporary stitching folder:\n" + dir);

    files = getFileList(groupDir);
    for (i = 0; i < files.length; i++) {
        name = files[i];
        if (!endsWith(name, "/") && isTile(name)) {
            base = tileBase(name);
            if (base == group) {
                File.copy(groupDir + name, dir + name);
                if (!File.exists(dir + name)) {
                    cleanupStitchDirectory(dir);
                    exit("Could not prepare tile for stitching:\n" + groupDir + name);
                }
            }
        }
    }
    return dir;
}


function cleanupStitchDirectory(dir) {
    if (!File.isDirectory(dir))
        return;
    files = getFileList(dir);
    for (i = 0; i < files.length; i++)
        if (!endsWith(files[i], "/"))
            File.delete(dir + files[i]);
    File.delete(dir);
}


function writeAutomaticLayout(dir, group, logText, minimumCorrelation) {
    n = countGroupTiles(dir, group);
    names = newArray(n);
    files = getFileList(dir);
    Array.sort(files);
    k = 0;
    for (i = 0; i < files.length; i++) {
        name = files[i];
        if (!endsWith(name, "/") && isTile(name)) {
            base = tileBase(name);
            if (base == group) {
                names[k] = name;
                k++;
            }
        }
    }

    edges = newArray(n * n);
    lines = split(logText, "\n");
    marker = " correlation (R)=";
    badMarker = "Identified link between ";

    for (i = 0; i < lines.length; i++) {
        line = replace(lines[i], "\r", "");
        p = indexOf(line, marker);
        arrow = indexOf(line, " <- ");
        if (p >= 0 && arrow >= 0) {
            left = substring(line, 0, arrow);
            right = substring(line, arrow + 4);
            b1 = indexOf(left, "[");
            b2 = indexOf(right, "[");
            rText = substring(line, p + lengthOf(marker));
            space = indexOf(rText, " ");
            if (space >= 0)
                rText = substring(rText, 0, space);
            if (b1 >= 0 && b2 >= 0 && parseFloat(rText) >= minimumCorrelation) {
                a = nameIndex(names, substring(left, 0, b1));
                b = nameIndex(names, substring(right, 0, b2));
                if (a >= 0 && b >= 0) {
                    edges[a * n + b] = 1;
                    edges[b * n + a] = 1;
                }
            }
        }

        p = indexOf(line, badMarker);
        if (p >= 0) {
            rest = substring(line, p + lengthOf(badMarker));
            b1 = indexOf(rest, "[");
            between = indexOf(rest, "] and ");
            if (b1 >= 0 && between >= 0) {
                right = substring(rest, between + 6);
                b2 = indexOf(right, "[");
                if (b2 >= 0) {
                    a = nameIndex(names, substring(rest, 0, b1));
                    b = nameIndex(names, substring(right, 0, b2));
                    if (a >= 0 && b >= 0) {
                        edges[a * n + b] = 0;
                        edges[b * n + a] = 0;
                    }
                }
            }
        }
    }

    parent = newArray(n);
    for (i = 0; i < n; i++)
        parent[i] = i;
    edgeCount = 0;
    for (i = 0; i < n; i++)
        for (j = i + 1; j < n; j++)
            if (edges[i * n + j]) {
                joinRoots(parent, i, j);
                edgeCount++;
            }

    components = 0;
    for (i = 0; i < n; i++)
        if (rootOf(parent, i) == i)
            components++;
    if (components <= 1)
        return components;

    registered = dir + "TileConfiguration.registered.txt";
    if (!File.exists(registered))
        return -1;

    x = newArray(n);
    y = newArray(n);
    found = newArray(n);
    configLines = split(File.openAsString(registered), "\n");
    for (i = 0; i < configLines.length; i++) {
        line = trim(replace(configLines[i], "\r", ""));
        semicolon = indexOf(line, ";");
        openParen = indexOf(line, "(");
        closeParen = lastIndexOf(line, ")");
        if (semicolon < 0 || openParen < 0 || closeParen < 0)
            continue;
        name = trim(substring(line, 0, semicolon));
        index = nameIndex(names, name);
        coords = substring(line, openParen + 1, closeParen);
        comma = indexOf(coords, ",");
        if (index >= 0 && comma >= 0) {
            x[index] = parseFloat(trim(substring(coords, 0, comma)));
            y[index] = parseFloat(trim(substring(coords, comma + 1)));
            found[index] = 1;
        }
    }
    for (i = 0; i < n; i++)
        if (!found[i])
            return -1;

    // ponytail: headless Fiji has no readable Log window, so each registered
    // non-zero tile is joined to the nearest zero-offset filename. Use the
    // Stitching Java API if non-consecutive components must work headlessly.
    if (edgeCount == 0) {
        for (i = 0; i < n; i++) {
            if (abs(x[i]) < 0.000001 && abs(y[i]) < 0.000001)
                continue;
            anchor = -1;
            distance = n + 1;
            for (j = 0; j < n; j++) {
                if (abs(x[j]) < 0.000001 && abs(y[j]) < 0.000001 && abs(i - j) < distance) {
                    anchor = j;
                    distance = abs(i - j);
                }
            }
            if (anchor >= 0)
                joinRoots(parent, anchor, i);
        }

        components = 0;
        for (i = 0; i < n; i++)
            if (rootOf(parent, i) == i)
                components++;
        if (components <= 1)
            return components;
    }

    width = newArray(n);
    height = newArray(n);
    for (i = 0; i < n; i++) {
        open(dir + names[i]);
        width[i] = getWidth();
        height[i] = getHeight();
        close();
    }

    placed = newArray(n);
    newX = newArray(n);
    newY = newArray(n);
    cursorX = 0;
    for (i = 0; i < n; i++) {
        root = rootOf(parent, i);
        if (placed[root])
            continue;
        placed[root] = 1;
        minX = 1e30;
        minY = 1e30;
        maxX = -1e30;
        for (j = 0; j < n; j++) {
            if (rootOf(parent, j) == root) {
                minX = minOf(minX, x[j]);
                minY = minOf(minY, y[j]);
                maxX = maxOf(maxX, x[j] + width[j]);
            }
        }
        for (j = 0; j < n; j++) {
            if (rootOf(parent, j) == root) {
                newX[j] = cursorX + x[j] - minX;
                newY[j] = y[j] - minY;
            }
        }
        cursorX = cursorX + maxX - minX;
    }

    layout = "dim = 2\n\n# Define the image coordinates\n";
    for (i = 0; i < n; i++)
        layout = layout + names[i] + "; ; (" + d2s(newX[i], 6) + ", " + d2s(newY[i], 6) + ")\n";
    File.saveString(layout, dir + "TileConfiguration.automatic.txt");
    return components;
}


function nameIndex(names, name) {
    for (i = 0; i < names.length; i++)
        if (names[i] == name)
            return i;
    return -1;
}


function rootOf(parent, index) {
    while (parent[index] != index)
        index = parent[index];
    return index;
}


function joinRoots(parent, a, b) {
    a = rootOf(parent, a);
    b = rootOf(parent, b);
    if (a < b)
        parent[b] = a;
    else if (b < a)
        parent[a] = b;
}
