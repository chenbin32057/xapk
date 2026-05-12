package com.xapk.installer;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_INSTALL = 1001;
    private static final String MANIFEST_NAME = "manifest.json";

    private TextView tvStatus, tvAppName, tvPackageName, tvProgress;
    private ProgressBar progressBar;
    private MaterialButton btnSelect, btnInstall, btnOpen;

    private File extractedDir;
    private File baseApk;
    private final List<File> splitApks = new ArrayList<>();
    private File obbFile;
    private String targetPackage;
    private String appName;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean granted = result.get(Manifest.permission.READ_EXTERNAL_STORAGE);
                if (granted == null || !granted) {
                    tvStatus.setText(R.string.permission_required);
                }
            });

    private final ActivityResultLauncher<Intent> installResultLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    tvStatus.setText(R.string.install_success);
                    tvStatus.setTextColor(getColor(R.color.success));
                    progressBar.setVisibility(View.GONE);
                    tvProgress.setVisibility(View.GONE);
                    btnInstall.setVisibility(View.GONE);
                    btnOpen.setVisibility(View.VISIBLE);
                } else {
                    tvStatus.setText(R.string.install_failed);
                    tvStatus.setTextColor(getColor(R.color.error));
                    progressBar.setVisibility(View.GONE);
                    tvProgress.setVisibility(View.GONE);
                    btnInstall.setEnabled(true);
                }
            });

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        processXapk(uri);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvAppName = findViewById(R.id.tvAppName);
        tvPackageName = findViewById(R.id.tvPackageName);
        tvProgress = findViewById(R.id.tvProgress);
        progressBar = findViewById(R.id.progressBar);
        btnSelect = findViewById(R.id.btnSelect);
        btnInstall = findViewById(R.id.btnInstall);
        btnOpen = findViewById(R.id.btnOpen);

        extractedDir = new File(getCacheDir(), "xapk_extracted");

        btnSelect.setOnClickListener(v -> checkPermissionsAndPick());
        btnInstall.setOnClickListener(v -> startInstallation());
        btnOpen.setOnClickListener(v -> openInstalledApp());

        checkPermissions();
        handleIncomingIntent();
    }

    private void handleIncomingIntent() {
        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            processXapk(intent.getData());
        }
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                });
            }
        }
    }

    private void checkPermissionsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
                return;
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                });
                return;
            }
        }
        pickFile();
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String[] mimeTypes = {"application/xapk-package-archive", "application/vnd.apkm", "application/zip", "application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        filePickerLauncher.launch(intent);
    }

    private void processXapk(Uri uri) {
        resetState();
        tvStatus.setText(R.string.processing);
        tvStatus.setTextColor(getColor(R.color.text_primary));
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        btnSelect.setEnabled(false);

        new Thread(() -> {
            File tempXapk = null;
            try {
                deleteRecursive(extractedDir);
                extractedDir.mkdirs();

                tempXapk = new File(getCacheDir(), "temp.xapk");
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(tempXapk)) {
                    copyStream(is, fos);
                }

                try (ZipFile zipFile = new ZipFile(tempXapk)) {
                    int totalFiles = zipFile.size();
                    int extracted = 0;

                    JSONObject manifest = null;

                    ZipEntry manifestEntry = zipFile.getEntry(MANIFEST_NAME);
                    if (manifestEntry == null) {
                        manifestEntry = zipFile.getEntry("manifest.json");
                    }

                    if (manifestEntry != null) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(zipFile.getInputStream(manifestEntry)))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line);
                            }
                        }
                        manifest = new JSONObject(sb.toString());
                        targetPackage = manifest.optString("package_name", "");
                        appName = manifest.optString("name", "");
                    }

                    Enumeration<? extends ZipEntry> entries = zipFile.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;

                        String name = entry.getName();
                        File outFile = new File(extractedDir, name);
                        outFile.getParentFile().mkdirs();

                        try (InputStream is = zipFile.getInputStream(entry);
                             FileOutputStream fos = new FileOutputStream(outFile)) {
                            copyStream(is, fos);
                        }

                        extracted++;
                        int progress = (extracted * 100) / totalFiles;
                        String progressText = getString(R.string.extracting, extracted, totalFiles);

                        int finalProgress = progress;
                        runOnUiThread(() -> {
                            progressBar.setProgress(finalProgress);
                            tvProgress.setText(progressText);
                        });

                        String lowerName = name.toLowerCase();
                        String pkgApk = (manifest != null) ? manifest.optString("package_name", "") + ".apk" : "";
                        if (lowerName.equals("base.apk") || (!pkgApk.isEmpty() && !lowerName.contains("config.") && lowerName.equals(pkgApk))) {
                            baseApk = outFile;
                        } else if (lowerName.endsWith(".apk")) {
                            splitApks.add(outFile);
                        } else if (lowerName.endsWith(".obb")) {
                            obbFile = outFile;
                        }
                    }

                    if (baseApk == null && !splitApks.isEmpty()) {
                        baseApk = splitApks.remove(0);
                    } else if (baseApk == null) {
                        List<File> allApks = findFilesRecursive(extractedDir, ".apk");
                        if (!allApks.isEmpty()) {
                            baseApk = allApks.get(0);
                            for (int i = 1; i < allApks.size(); i++) {
                                splitApks.add(allApks.get(i));
                            }
                        }
                    }
                }

                if (tempXapk != null) tempXapk.delete();

                File finalBaseApk = baseApk;
                runOnUiThread(() -> {
                    if (finalBaseApk != null && finalBaseApk.exists()) {
                        if (appName == null || appName.isEmpty()) {
                            PackageInfo pi = getPackageManager().getPackageArchiveInfo(
                                    finalBaseApk.getAbsolutePath(), PackageManager.GET_META_DATA);
                            if (pi != null && pi.applicationInfo != null) {
                                appName = pi.applicationInfo.loadLabel(getPackageManager()).toString();
                                targetPackage = pi.packageName;
                            }
                        }

                        tvStatus.setText("已解析 — 准备安装");
                        tvStatus.setTextColor(getColor(R.color.success));
                        tvAppName.setVisibility(View.VISIBLE);
                        tvAppName.setText((appName == null || appName.isEmpty()) ? "未知应用" : appName);
                        tvPackageName.setVisibility(View.VISIBLE);
                        tvPackageName.setText(targetPackage == null ? "" : targetPackage);
                        progressBar.setVisibility(View.GONE);
                        tvProgress.setVisibility(View.GONE);
                        btnInstall.setVisibility(View.VISIBLE);
                        btnInstall.setEnabled(true);
                    } else {
                        tvStatus.setText(R.string.invalid_xapk);
                        tvStatus.setTextColor(getColor(R.color.error));
                        progressBar.setVisibility(View.GONE);
                        tvProgress.setVisibility(View.GONE);
                    }
                    btnSelect.setEnabled(true);
                });

            } catch (Exception e) {
                File finalTemp = tempXapk;
                String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
                runOnUiThread(() -> {
                    tvStatus.setText("解析失败: " + errMsg);
                    tvStatus.setTextColor(getColor(R.color.error));
                    progressBar.setVisibility(View.GONE);
                    tvProgress.setVisibility(View.GONE);
                    btnSelect.setEnabled(true);
                });
                if (finalTemp != null) finalTemp.delete();
            }
        }).start();
    }

    private void startInstallation() {
        btnInstall.setEnabled(false);
        tvStatus.setText(R.string.installing);
        tvStatus.setTextColor(getColor(R.color.text_primary));

        new Thread(() -> {
            try {
                if (obbFile != null && obbFile.exists() && targetPackage != null && !targetPackage.isEmpty()) {
                    runOnUiThread(() -> tvStatus.setText(R.string.obb_copying));
                    copyObbFile();
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !splitApks.isEmpty()) {
                    installWithSession();
                } else {
                    runOnUiThread(this::installSingleApk);
                }
            } catch (Exception e) {
                String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
                runOnUiThread(() -> {
                    tvStatus.setText("安装失败: " + errMsg);
                    tvStatus.setTextColor(getColor(R.color.error));
                    btnInstall.setEnabled(true);
                });
            }
        }).start();
    }

    private void installSingleApk() {
        try {
            if (baseApk == null || !baseApk.exists()) {
                tvStatus.setText("APK 文件不存在");
                tvStatus.setTextColor(getColor(R.color.error));
                btnInstall.setEnabled(true);
                return;
            }
            Uri apkUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", baseApk);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(apkUri);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installResultLauncher.launch(intent);
        } catch (Exception e) {
            tvStatus.setText("安装失败: " + e.getMessage());
            tvStatus.setTextColor(getColor(R.color.error));
            btnInstall.setEnabled(true);
        }
    }

    private void installWithSession() throws Exception {
        PackageManager pm = getPackageManager();
        PackageInstaller installer = pm.getPackageInstaller();

        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);

        try {
            if (baseApk != null) {
                addApkToSession(session, baseApk, baseApk.getName());
            }
            for (File split : splitApks) {
                addApkToSession(session, split, split.getName());
            }

            Intent callbackIntent = new Intent(this, InstallResultReceiver.class);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this,
                    REQUEST_INSTALL, callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            session.commit(pendingIntent.getIntentSender());
        } finally {
            session.close();
        }

        runOnUiThread(() -> {
            tvStatus.setText("正在通过系统安装器安装…");
            tvStatus.setTextColor(getColor(R.color.text_primary));
        });
    }

    private void addApkToSession(PackageInstaller.Session session, File apkFile, String name) throws Exception {
        try (OutputStream os = session.openWrite(name, 0, apkFile.length());
             FileInputStream fis = new FileInputStream(apkFile)) {
            byte[] buffer = new byte[65536];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
            session.fsync(os);
        }
    }

    private void copyObbFile() {
        try {
            if (targetPackage == null) return;
            File obbDir = new File(Environment.getExternalStorageDirectory(),
                    "Android/obb/" + targetPackage);
            if (!obbDir.exists()) {
                obbDir.mkdirs();
            }
            File destFile = new File(obbDir, obbFile.getName());
            try (FileInputStream fis = new FileInputStream(obbFile);
                 FileOutputStream fos = new FileOutputStream(destFile)) {
                copyStream(fis, fos);
            }
        } catch (Exception e) {
            runOnUiThread(() -> tvProgress.setText("OBB 复制失败: " + e.getMessage()));
        }
    }

    private void openInstalledApp() {
        if (targetPackage != null && !targetPackage.isEmpty()) {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(targetPackage);
            if (launchIntent != null) {
                startActivity(launchIntent);
            } else {
                tvStatus.setText("无法打开应用，请手动启动");
            }
        }
    }

    private void resetState() {
        baseApk = null;
        splitApks.clear();
        obbFile = null;
        targetPackage = null;
        appName = null;
        tvAppName.setVisibility(View.GONE);
        tvPackageName.setVisibility(View.GONE);
        btnInstall.setVisibility(View.GONE);
        btnOpen.setVisibility(View.GONE);
        progressBar.setProgress(0);
    }

    private void copyStream(InputStream is, OutputStream os) throws Exception {
        byte[] buffer = new byte[65536];
        int len;
        while ((len = is.read(buffer)) != -1) {
            os.write(buffer, 0, len);
        }
        os.flush();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private static List<File> findFilesRecursive(File dir, String suffix) {
        List<File> result = new ArrayList<>();
        if (dir == null || !dir.exists()) return result;
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isDirectory()) {
                result.addAll(findFilesRecursive(f, suffix));
            } else if (f.getName().toLowerCase().endsWith(suffix)) {
                result.add(f);
            }
        }
        return result;
    }
}
