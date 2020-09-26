package unzen.android.test.cpp.exec;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import unzen.android.test.cpp.exec.cppmodule.CppModule;

import static unzen.android.test.cpp.exec.Assert.assertFalse;
import static unzen.android.test.cpp.exec.Assert.assertTrue;
import static unzen.android.test.cpp.exec.FileUtils.fileListedInDir;
import static unzen.android.test.cpp.exec.Utils.executeFromAppFiles;
import static unzen.android.test.cpp.exec.Utils.format;
import static unzen.android.test.cpp.exec.Utils.getExecOutput;
import static unzen.android.test.cpp.exec.Utils.parseVerFromFile;
import static unzen.android.test.cpp.exec.Utils.parseVerFromOutput;

/**
 * Android 10 W^X policy:
 *  https://issuetracker.google.com/issues/128554619
 *  https://developer.android.com/ndk/guides/wrap-script
 *  https://github.com/termux/termux-packages/wiki/Termux-and-Android-10
 */
public class MainActivity extends Activity {

    static private final String FOO_NAME = "jnifoo";
    static public final String FOO = "lib" + FOO_NAME + ".so";
    static public final String BAR = "execbar";
    static public final String BAZ = "execbaz";
    static public final String QUX = "qux.sh";
    static private Set<String> EXECS = new HashSet<>(Arrays.asList(FOO, QUX, BAR, BAZ));

    static private class Report {

        public final String name;
        public final Map<String, Integer> abisToVers;
        public final long totalSize;
        public final int verFromOutput;

        public boolean versInSync(int version) {
            for (Integer v : abisToVers.values()) {
                if (version != v) {
                    return false;
                }
            }
            return true;
        }

        public String header() {
            return format("%s v%d, %d B", name, verFromOutput, totalSize);
        }

        public String body() {
            StringBuilder result = new StringBuilder();
            for (Map.Entry<String, Integer> entry : abisToVers.entrySet()) {
                if (result.length() > 0) {
                    result.append(", ");
                }
                result.append(entry.getKey()).append(" ").append("v").append(entry.getValue());
            }
            return Utils.shortenAbisNames(result.toString());
        }

        @Override
        public String toString() {
            return header() + "\n" + body();
        }

        private Report(String name, Map<String, Integer> abisToVers, long size, int verFromOutput) {
            this.name = name;
            this.abisToVers = abisToVers;
            this.totalSize = size;
            this.verFromOutput = verFromOutput;
        }
    }

    static private void checkOutput(String elfName, String actualOut) {
        String expected = format("I'm %s! UNZEN-VERSION-", elfName);
        String message = format("Expected: %s, actual: %s", expected, actualOut);
        assertTrue(actualOut.startsWith(expected), message);
    }

    private Report getJniReport(File apkDir) throws IOException {
        File apkLibsDir = new File(apkDir, "lib");
        Map<String, Integer> abisToVers = new HashMap<>();
        long totalSize = 0;
        for (File abiDir : Objects.requireNonNull(apkLibsDir.listFiles())) {
            File foo = new File(abiDir, FOO);
            if (!foo.exists()) {
                continue;
            }
            abisToVers.put(abiDir.getName(), parseVerFromFile(foo));
            totalSize += foo.length();
        }
        String output = CppModule.getStringFromJni();
        checkOutput(FOO, output);
        return new Report(FOO, abisToVers, totalSize, parseVerFromOutput(output));
    }

    private int execsVerFromOutput(File execsDir) throws IOException {
        String barOut = getExecOutput(new File(execsDir, BAR));
        checkOutput(BAR, barOut);
        int barVer = parseVerFromOutput(barOut);
        String bazOut = getExecOutput(new File(execsDir, BAZ));
        checkOutput(BAZ, bazOut);
        int bazVer = parseVerFromOutput(barOut);
        assertTrue(barVer == bazVer, format("VerFromOutput %d != %d", barVer, bazVer));
        return barVer;
    }

    private void symlink(String target, String link) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Files.createSymbolicLink(Paths.get(link), Paths.get(target));
        }
    }

    private int execsVerFromOutputSymlinks(File execsDir) throws IOException {
        File linksDir = new File(getCacheDir(), "exec-links");
        assertTrue(linksDir.exists() || linksDir.mkdirs());
        for (String exe : new String[] {BAR, BAZ}) {
            File target = new File(execsDir, exe);
            File symlink = new File(linksDir, exe);
            if (symlink.exists()) {
                assertTrue(symlink.delete());
            }
            assertFalse(symlink.exists());
            if (fileListedInDir(linksDir, symlink)) {
                assertTrue(symlink.delete());
                warn("Symlink exists() is false but its name is present in parent listing: %s",
                        symlink.getAbsolutePath());
            }
            symlink(target.getAbsolutePath(), symlink.getAbsolutePath());
            assertTrue(symlink.exists());
        }
        return execsVerFromOutput(linksDir);
    }

    private Report getExecReport(File apkDir) throws IOException {
        File apkLibsDir = new File(apkDir, "lib");
        Map<String, Integer> abisToVers = new HashMap<>();
        long totalSize = 0;
        for (File abiDir : Objects.requireNonNull(apkLibsDir.listFiles())) {
            Set<String> names = new HashSet<>(Arrays.asList(Objects.requireNonNull(abiDir.list())));
            if (names.size() == 1 && names.contains(QUX)) {
                continue;
            }
            if (names.size() == 2 && names.contains(FOO) && names.contains(QUX)) {
                error("Build missing %s and %s.", BAR, BAZ);
                return null;
            }
            assertTrue(EXECS.equals(names), names.toString());
            File bar = new File(abiDir, BAR);
            File baz = new File(abiDir, BAZ);
            int barVer = parseVerFromFile(bar);
            int bazVer = parseVerFromFile(baz);
            assertTrue(barVer == bazVer, format("VerFromFile %d != %d", barVer, bazVer));
            abisToVers.put(abiDir.getName(), barVer);
            totalSize += bar.length() + baz.length();
        }
        int verFromOutput = -1;
        if (executeFromAppFiles()) {
            for (String abi : Utils.getSupportedAbis()) {
                if (abisToVers.containsKey(abi)) {
                    verFromOutput = execsVerFromOutput(new File(apkLibsDir, abi));
                    break;
                }
            }
        } else {
            File execsDir = new File(getApplicationInfo().nativeLibraryDir);
            int verFromOutputDirect = execsVerFromOutput(execsDir);
            int verFromOutputLinks = execsVerFromOutputSymlinks(execsDir);
            assertTrue(verFromOutputDirect == verFromOutputLinks);
            verFromOutput = verFromOutputLinks;
        }
        assertFalse(verFromOutput == -1);
        return new Report("barbaz", abisToVers, totalSize, verFromOutput);
    }

    private final ArrayList<String> messages = new ArrayList<>();
    private final ArrayList<String> warns = new ArrayList<>();
    private final ArrayList<String> errors = new ArrayList<>();

    private void message(String m) {
        messages.add(m);
    }

    private void message(String format, Object... args) {
        message(format(format, args));
    }

    private void warn(String m) {
        warns.add(m);
    }

    private void warn(String format, Object... args) {
        warn(format(format, args));
    }

    private void error(String m) {
        errors.add(m);
    }

    private void error(String format, Object... args) {
        error(format(format, args));
    }

    @SuppressWarnings("ConstantConditions")
    private void checkJniExeReports(Report jniReport, Report exeReport) {
        if (exeReport == null) {
            return;
        }
        assertTrue(jniReport.abisToVers.equals(exeReport.abisToVers));
        assertTrue(jniReport.verFromOutput == BuildConfig.BASE_VERSION_CODE);
        assertTrue(exeReport.verFromOutput == BuildConfig.BASE_VERSION_CODE);
        if (BuildConfig.FLAVOR.equals("fat")) {
            assertTrue(BuildConfig.VERSION_CODE == BuildConfig.BASE_VERSION_CODE);
            assertTrue(Arrays.asList(1, 2, 3, 4).contains(jniReport.abisToVers.size()));
        } else {
            assertTrue(jniReport.abisToVers.size() == 1);
            if (BuildConfig.FLAVOR.equals("a32")) {
                assertTrue(BuildConfig.VERSION_CODE == BuildConfig.BASE_VERSION_CODE + 1);
            } else if (BuildConfig.FLAVOR.equals("a64")) {
                assertTrue(BuildConfig.VERSION_CODE == BuildConfig.BASE_VERSION_CODE + 2);
            } else if (BuildConfig.FLAVOR.equals("x32")) {
                assertTrue(BuildConfig.VERSION_CODE == BuildConfig.BASE_VERSION_CODE + 3);
            } else if (BuildConfig.FLAVOR.equals("x64")) {
                assertTrue(BuildConfig.VERSION_CODE == BuildConfig.BASE_VERSION_CODE + 4);
            }
        }
        if (!jniReport.versInSync(BuildConfig.BASE_VERSION_CODE)) {
            warn("Versions between ABIs doesn't match. That's may be due to build performed by"
                    + " Android Studio's \"Run\" action, that makes new build only for ABI of"
                    + " the \"Run\" target's device.");
        }
        if (BuildConfig.FLAVOR.equals("fat") && jniReport.abisToVers.size() != 4) {
            warn("Flavor \"fat\" has only %d ABIs, expected 4 ABIs. That's may be due to"
                            + " build performed by Android Studio's \"Run\" action, that makes"
                            + " new build only for ABI of the \"Run\" target's device.",
                    jniReport.abisToVers.size());
        }
    }

    private void displayReport(Report jniReport, Report exeReport, TextView textView) {
        ArrayList<String> header = new ArrayList<>();
        header.add(format("Java v%s, Cpp v%d", BuildConfig.VERSION_NAME, BuildConfig.BASE_VERSION_CODE));
        header.add("\n" + jniReport.toString());
        if (exeReport != null) {
            header.add("\n" + exeReport.toString());
        }
        String text = TextUtils.join("\n", header);
        if (!errors.isEmpty()) {
            text = format("%s%n%n%nERRORS%n%n%s", text, TextUtils.join("\n\n", errors));
        }
        if (!warns.isEmpty()) {
            text = format("%s%n%n%nWARNINGS%n%n%s", text, TextUtils.join("\n\n", warns));
        }
        if (!messages.isEmpty()) {
            text = format("%s%n%n%nMESSAGES%n%n%s", text, TextUtils.join("\n", messages));
        }
        textView.setText(text);
        if (!errors.isEmpty()) {
            textView.setTextColor(0xffff0000);
        } else if (!warns.isEmpty()) {
            textView.setTextColor(0xfffc940a);
        } else {
            textView.setTextColor(0xff00ff55);
        }
    }

    private void nativeLibraryDirReport() {
        message("getApplicationInfo().nativeLibraryDir");
        String[] execs = new File(getApplicationInfo().nativeLibraryDir).list();
        message("{" + TextUtils.join(", ", Objects.requireNonNull(execs)) + "}");
        message(getApplicationInfo().nativeLibraryDir);
    }

    private File unpackApk() throws IOException {
        File apkDir = new File(getCacheDir(), "unzen-apk");
        FileUtils.deleteDirectory(apkDir);
        assertTrue(!apkDir.exists() && apkDir.mkdirs());
        ZipUtils.extract(new File(getPackageResourcePath()), apkDir);
        File assetsDir = new File(apkDir, "assets");
        File dummy = new File(assetsDir, "dummy.txt");
        assertTrue(dummy.exists() && dummy.length() > 0);
        File dummyLib = new File(assetsDir, "dummy-lib.txt");
        assertTrue(dummyLib.exists() && dummyLib.length() > 0);
        return apkDir;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        try {
            nativeLibraryDirReport();
            File apkDir = unpackApk();
            Report jniReport = getJniReport(apkDir);
            Report exeReport = getExecReport(apkDir);
            checkJniExeReports(jniReport, exeReport);
            displayReport(jniReport, exeReport, findViewById(R.id.main_text));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
