package main;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import panes.JErrorPane;
import sorts.templates.Sort;
import sorts.templates.SortComparator;

/*
 *
The MIT License (MIT)

Copyright (c) 2019 Luke Hutchison

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 *
 */

final public class SortAnalyzer {
    private ArrayList<Sort> comparisonSorts;
    private ArrayList<Sort> distributionSorts;
    private ArrayList<String> invalidSorts;
    private ArrayList<String> suggestions;

    private String sortErrorMsg;

    private ArrayVisualizer arrayVisualizer;

    public static class SortPair {
        public int id;
        public Class<?> sortClass;
        public String listName;
        public String category;
        public boolean usesComparisons;

        public static String[] getListNames(SortPair[] sorts) {
            String[] result = new String[sorts.length];
            for (int i = 0; i < sorts.length; i++) {
                result[i] = sorts[i].listName;
            }
            return result;
        }

        public static String[] getCategories(SortPair[] sorts) {
            HashSet<String> result = new HashSet<>();
            for (int i = 0; i < sorts.length; i++) {
                result.add(sorts[i].category);
            }
            String[] resultArray = result.toArray(new String[result.size()]);
            Arrays.sort(resultArray);
            return resultArray;
        }
    }

    public SortAnalyzer(ArrayVisualizer arrayVisualizer) {
        this.comparisonSorts = new ArrayList<>();
        this.distributionSorts = new ArrayList<>();
        this.invalidSorts = new ArrayList<>();
        this.suggestions = new ArrayList<>();

        this.arrayVisualizer = arrayVisualizer;
    }

    private boolean compileSingle(String name, ClassLoader loader) {
        try {
            Class<?> sortClass;
            try {
                if (loader == null)
                    sortClass = Class.forName(name);
                else
                    sortClass = Class.forName(name, true, loader);
            } catch (ClassNotFoundException e) {
                return true;
            }
            // System.out.println(sortClass.getConstructors()[0].getParameterTypes()[0].hashCode());
            Constructor<?> newSort = sortClass.getConstructor(new Class[] {ArrayVisualizer.class});
            // Constructor<?> newSort = sortClass.getConstructors()[0];
            Sort sort = (Sort) newSort.newInstance(this.arrayVisualizer);

            try {
                if (verifySort(sort)) {
                    String suggestion = checkForSuggestions(sort);
                    if (!suggestion.isEmpty()) {
                        suggestions.add(suggestion);
                    }
                    if (sort.isComparisonBased()) {
                        comparisonSorts.add(sort);
                    } else {
                        distributionSorts.add(sort);
                    }
                } else {
                    throw new Exception();
                }
            } catch (Exception e) {
                invalidSorts.add(sort.getClass().getName() + " (" + this.sortErrorMsg + ")");
                return false;
            }
        } catch (Exception e) {
            JErrorPane.invokeErrorMessage(e, "Could not compile " + name);
            invalidSorts.add(name + " (failed to compile)");
            return false;
        }
        return true;
    }

    public void analyzeSorts() {
        ClassGraph classGraph = new ClassGraph();
        classGraph.whitelistPackages("sorts");
        classGraph.blacklistPackages("sorts.templates");
        classGraph.blacklistPaths("cache/*");

        try (ScanResult scanResult = classGraph.scan()) {
            List<ClassInfo> sortFiles;
            sortFiles = scanResult.getAllClasses();

            for (int i = 0; i < sortFiles.size(); i++) {
                if (sortFiles.get(i).getName().contains("$")) continue; // Ignore inner classes
                this.compileSingle(sortFiles.get(i).getName(), null);
            }
            sortSorts();
        } catch (Exception e) {
            JErrorPane.invokeErrorMessage(e);
        }
    }

    public boolean importSort(File file, boolean showConfirmation) {
        Pattern packagePattern = Pattern.compile("^\\s*package ([a-zA-Z\\.]+);");
        String contents;
        try {
            contents = new String(Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            JErrorPane.invokeErrorMessage(e);
            return false;
        }
        Matcher matcher = packagePattern.matcher(contents);
        if (!matcher.find()) {
            JErrorPane.invokeCustomErrorMessage("No package specifed");
            return false;
        }
        String packageName = matcher.group(1);
        String name = packageName + "." + file.getName().split("\\.")[0];

        final File CACHE_DIR = new File("./cache/");
        CACHE_DIR.mkdirs();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> jFiles = fileManager.getJavaFileObjects(file);
        int success;
        try {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Arrays.asList(CACHE_DIR));
            CompilationTask task = compiler.getTask(
                null,        // out
                fileManager, // fileManager
                null,        // diagnosticListener
                null,        // options
                null,        // classes
                jFiles       // compilationUnits
            );
            try {
                // Code that would work except that reflection is safer (I think) when using APIs that may be removed
                // com.sun.tools.javac.main.Main.Result result = ((com.sun.tools.javac.api.JavacTaskImpl)task).doCall();
                // success = result.exitCode;
                Method doCall = task.getClass().getDeclaredMethod("doCall");
                Object result = doCall.invoke(task);
                success = (int)result.getClass().getDeclaredField("exitCode").get(result);
            } catch (Exception e) {
                success = task.call() ? 0 : 1;
            }
        } catch (Exception e) {
            JErrorPane.invokeErrorMessage(e, "Sort Import");
            success = -1;
        }
        if (success != 0) {
            JErrorPane.invokeCustomErrorMessage("Failed to compile: " + file + "\nError code " + success);
            return false;
        }

        try {
            if (!compileSingle(name, URLClassLoader.newInstance(new URL[] { CACHE_DIR.toURI().toURL() })))
                return false;
        } catch (Exception e) {
            JErrorPane.invokeErrorMessage(e);
            return false;
        }

        if (showConfirmation) {
            sortSorts();
            arrayVisualizer.refreshSorts();
            JOptionPane.showMessageDialog(null, "Successfully imported sort " + name, "Import Sort", JOptionPane.INFORMATION_MESSAGE);
        }
        return true;
    }

    public boolean importSort(File file) {
        return importSort(file, true);
    }

    public void sortSorts() {
        SortComparator sortComparator = new SortComparator();
        Collections.sort(comparisonSorts, sortComparator);
        Collections.sort(distributionSorts, sortComparator);
    }

    private boolean verifySort(Sort sort) {
        if (!sort.isSortEnabled()) {
            this.sortErrorMsg = "manually disabled";
            return false;
        }
        if (sort.getSortListName().equals("")) {
            this.sortErrorMsg = "missing 'Choose Sort' name";
            return false;
        }
        if (sort.getRunAllSortsName().equals("")) {
            this.sortErrorMsg = "missing 'Run All' name";
            return false;
        }
        if (sort.getRunSortName().equals("")) {
            this.sortErrorMsg = "missing 'Run Sort' name";
            return false;
        }
        if (sort.getCategory().equals("")) {
            this.sortErrorMsg = "missing category";
            return false;
        }

        return true;
    }

    private static String checkForSuggestions(Sort sort) {
        StringBuilder suggestions = new StringBuilder();
        boolean warned = false;

        if (sort.isBogoSort() && !sort.isUnreasonablySlow()) {
            suggestions.append("- " + sort.getRunSortName() + " is a bogosort. It should be marked 'unreasonably slow'.\n");
            warned = true;
        }
        if (sort.isUnreasonablySlow() && sort.getUnreasonableLimit() == 0) {
            suggestions.append("- A warning will pop up every time you select " + sort.getRunSortName() + ". You might want to change its 'unreasonable limit'.\n");
            warned = true;
        }
        if (!sort.isUnreasonablySlow() && sort.getUnreasonableLimit() != 0) {
            suggestions.append("- You might want to set " + sort.getRunSortName() + "'s 'unreasonable limit' to 0. It's not marked 'unreasonably slow'.\n");
            warned = true;
        }
        if (sort.isRadixSort() && !sort.usesBuckets()) {
            suggestions.append("- " + sort.getRunSortName() + " is a radix sort and should also be classified as a bucket sort.\n");
            warned = true;
        }
        if (sort.isRadixSort() && sort.isComparisonBased()) {
            suggestions.append("- " + sort.getRunSortName() + " is a radix sort. It probably shouldn't be labelled as a comparison-based sort.\n");
            warned = true;
        }

        if (warned) {
            suggestions.deleteCharAt(suggestions.length() - 1);
        }
        return suggestions.toString();
    }

    public SortPair[] getComparisonSorts() {
        SortPair[] ComparisonSorts = new SortPair[comparisonSorts.size()];

        for (int i = 0; i < ComparisonSorts.length; i++) {
            ComparisonSorts[i] = new SortPair();
            ComparisonSorts[i].id = i;
            ComparisonSorts[i].sortClass = comparisonSorts.get(i).getClass();
            ComparisonSorts[i].listName = comparisonSorts.get(i).getSortListName();
            ComparisonSorts[i].category = comparisonSorts.get(i).getCategory();
            ComparisonSorts[i].usesComparisons = true;
        }

        return ComparisonSorts;
    }
    public SortPair[] getDistributionSorts() {
        SortPair[] DistributionSorts = new SortPair[distributionSorts.size()];

        for (int i = 0; i < DistributionSorts.length; i++) {
            DistributionSorts[i] = new SortPair();
            DistributionSorts[i].id = i;
            DistributionSorts[i].sortClass = distributionSorts.get(i).getClass();
            DistributionSorts[i].listName = distributionSorts.get(i).getSortListName();
            DistributionSorts[i].category = distributionSorts.get(i).getCategory();
            DistributionSorts[i].usesComparisons = false;
        }

        return DistributionSorts;
    }
    public String[] getInvalidSorts() {
        if (invalidSorts.size() < 1) {
            return null;
        }

        String[] InvalidSorts = new String[invalidSorts.size()];
        InvalidSorts = invalidSorts.toArray(InvalidSorts);

        return InvalidSorts;
    }
    public String[] getSuggestions() {
        if (suggestions.size() < 1) {
            return null;
        }

        String[] allSuggestions = new String[suggestions.size()];
        allSuggestions = suggestions.toArray(allSuggestions);

        return allSuggestions;
    }
}