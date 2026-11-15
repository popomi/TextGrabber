package com.screen.textgrabber;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用主界面
 * 提供实时抓包开关、一键清空文本与数据库、一键导出全部文本/CSV/DB、全部抓取存档浏览（按时间抓包 / 数据库无障碍包 / 纯文本）及一键复制能力。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final long REFRESH_INTERVAL_MS = 2000L;
    private static final long SEARCH_DEBOUNCE_MS = 220L;
    private static final int CLIPBOARD_SAFE_MAX_CHARS = 400_000;

    private SharedPreferences sp;
    private DBManager dbManager;
    private ExecutorService executor;
    private Handler mainHandler;

    private SwitchCompat toggleSwitch;
    private TextView tvCounter;
    private TextView tvDbSize;
    private TextView tvServiceStatus;
    private TextView tvArchiveSummary;
    private TextView tvEmpty;

    private MaterialButton btnClearAll;
    private MaterialButton btnExportAll;
    private MaterialButton btnCopyAll;
    private MaterialButton btnCopyPure;

    private MaterialButton btnModeTimeline;
    private MaterialButton btnModeDatabase;
    private MaterialButton btnModePlain;
    private MaterialButton btnPkgFilter;
    private MaterialButton btnSortOrder;
    private MaterialButton btnClearSearch;
    private EditText etSearch;
    private RecyclerView rvArchive;
    private ArchiveAdapter adapter;

    private int currentMode = ArchiveFormatter.MODE_TIMELINE_PACKETS;
    private boolean newestFirst = true;
    private String selectedPkgFilter = null;
    private String currentKeyword = "";
    private boolean suppressSearchWatcher = false;

    private long lastKnownDbCount = -1L;
    private long lastKnownDbMaxId = -1L;
    private List<CaptureRecord> currentRecords = new ArrayList<>();
    private List<CapturePacket> currentPackets = new ArrayList<>();
    private List<DBManager.PackageStat> currentPkgStats = new ArrayList<>();

    private final Map<String, String> appLabelCache = new ConcurrentHashMap<>();
    private ArchiveFormatter.AppLabelResolver appLabelResolver;

    private final Runnable periodicRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            checkStatsAndRefreshIfNeeded();
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private final Runnable debouncedSearchRunnable = new Runnable() {
        @Override
        public void run() {
            reloadArchiveData(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences("app_config", MODE_PRIVATE);
        dbManager = new DBManager(this);
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        currentMode = sp.getInt("view_mode", ArchiveFormatter.MODE_TIMELINE_PACKETS);
        newestFirst = sp.getBoolean("newest_first", true);

        appLabelResolver = new ArchiveFormatter.AppLabelResolver() {
            @Override
            public String resolveLabel(String pkgName) {
                return resolveAppDisplayLabel(pkgName);
            }
        };

        bindViews();
        setupInteractions();
        updateModeTabsUi();
        updateSortOrderUi();
        updateFilterButtonUi();
        refreshServiceStatusUi();
    }

    private void bindViews() {
        tvCounter = findViewById(R.id.tv_counter);
        tvDbSize = findViewById(R.id.tv_db_size);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvArchiveSummary = findViewById(R.id.tv_archive_summary);
        tvEmpty = findViewById(R.id.tv_empty);
        toggleSwitch = findViewById(R.id.btn_toggle);

        btnClearAll = findViewById(R.id.btn_clear_all);
        btnExportAll = findViewById(R.id.btn_export_all);
        btnCopyAll = findViewById(R.id.btn_copy_all);
        btnCopyPure = findViewById(R.id.btn_copy_pure);

        btnModeTimeline = findViewById(R.id.btn_mode_timeline);
        btnModeDatabase = findViewById(R.id.btn_mode_database);
        btnModePlain = findViewById(R.id.btn_mode_plain);
        btnPkgFilter = findViewById(R.id.btn_pkg_filter);
        btnSortOrder = findViewById(R.id.btn_sort_order);
        btnClearSearch = findViewById(R.id.btn_clear_search);
        etSearch = findViewById(R.id.et_search);
        rvArchive = findViewById(R.id.rv_archive);

        rvArchive.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ArchiveAdapter(appLabelResolver, new ArchiveAdapter.OnCopyListener() {
            @Override
            public void onCopyText(String text, boolean isFullMetadata) {
                copySingleItem(text, isFullMetadata);
            }
        });
        rvArchive.setAdapter(adapter);

        tvCounter.setText(getString(R.string.saved_count, 0, sp.getInt("saved_count", 0)));
        tvDbSize.setText(getString(R.string.db_size, "0 B"));
    }

    private void setupInteractions() {
        toggleSwitch.setChecked(sp.getBoolean("is_active", false));
        toggleSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean("is_active", isChecked).apply();
                if (isChecked && !isAccessibilitySettingsOn(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, R.string.permission_hint, Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }
            }
        });

        tvServiceStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        btnClearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAndClearAll();
            }
        });

        btnExportAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportAllArchiveText();
            }
        });

        btnCopyAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAllArchive(false);
            }
        });

        btnCopyPure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAllArchive(true);
            }
        });

        btnModeTimeline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchViewMode(ArchiveFormatter.MODE_TIMELINE_PACKETS);
            }
        });

        btnModeDatabase.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchViewMode(ArchiveFormatter.MODE_DB_RECORDS);
            }
        });

        btnModePlain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchViewMode(ArchiveFormatter.MODE_PLAIN_TEXT);
            }
        });

        btnPkgFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPackageFilterDialog();
            }
        });

        btnSortOrder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newestFirst = !newestFirst;
                sp.edit().putBoolean("newest_first", newestFirst).apply();
                updateSortOrderUi();
                reloadArchiveData(true);
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressSearchWatcher) {
                    return;
                }
                currentKeyword = s != null ? s.toString().trim() : "";
                mainHandler.removeCallbacks(debouncedSearchRunnable);
                mainHandler.postDelayed(debouncedSearchRunnable, SEARCH_DEBOUNCE_MS);
            }
        });

        etSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    mainHandler.removeCallbacks(debouncedSearchRunnable);
                    currentKeyword = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";
                    reloadArchiveData(false);
                    return true;
                }
                return false;
            }
        });

        btnClearSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedPkgFilter = null;
                currentKeyword = "";
                suppressSearchWatcher = true;
                etSearch.setText("");
                suppressSearchWatcher = false;
                updateFilterButtonUi();
                reloadArchiveData(true);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServiceStatusUi();
        mainHandler.removeCallbacks(periodicRefreshRunnable);
        mainHandler.post(periodicRefreshRunnable);
        reloadArchiveData(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(periodicRefreshRunnable);
        mainHandler.removeCallbacks(debouncedSearchRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void switchViewMode(int newMode) {
        if (currentMode == newMode) {
            return;
        }
        currentMode = newMode;
        sp.edit().putInt("view_mode", currentMode).apply();
        updateModeTabsUi();
        renderCurrentData(true);
    }

    private void updateModeTabsUi() {
        styleTabButton(btnModeTimeline, currentMode == ArchiveFormatter.MODE_TIMELINE_PACKETS);
        styleTabButton(btnModeDatabase, currentMode == ArchiveFormatter.MODE_DB_RECORDS);
        styleTabButton(btnModePlain, currentMode == ArchiveFormatter.MODE_PLAIN_TEXT);
    }

    private void styleTabButton(MaterialButton button, boolean selected) {
        if (selected) {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_primary));
            button.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.tab_bg));
            button.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    private void updateSortOrderUi() {
        btnSortOrder.setText(newestFirst ? R.string.sort_newest_first : R.string.sort_oldest_first);
    }

    private void updateFilterButtonUi() {
        long total = Math.max(0L, lastKnownDbCount);
        if (selectedPkgFilter == null) {
            btnPkgFilter.setText(getString(R.string.pkg_filter_all, (int) total));
        } else {
            int pkgCount = 0;
            for (DBManager.PackageStat stat : currentPkgStats) {
                if (selectedPkgFilter.equals(stat.pkgName)) {
                    pkgCount = stat.count;
                    break;
                }
            }
            String label = resolveAppDisplayLabel(selectedPkgFilter);
            btnPkgFilter.setText(getString(R.string.pkg_filter_selected, label, pkgCount));
        }
    }

    private void refreshServiceStatusUi() {
        boolean granted = isAccessibilitySettingsOn(this);
        if (granted) {
            tvServiceStatus.setText(R.string.accessibility_granted);
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.btn_success));
        } else {
            tvServiceStatus.setText(R.string.accessibility_not_granted);
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.btn_danger));
        }
    }

    private void checkStatsAndRefreshIfNeeded() {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final long[] sig = dbManager.getDbSignature();
                final long totalCount = sig[0];
                final long maxId = sig[1];
                final long dbBytes = computeDbFileBytes();
                final int sessionCount = sp.getInt("saved_count", 0);

                final boolean changed = (totalCount != lastKnownDbCount) || (maxId != lastKnownDbMaxId);
                final List<DBManager.PackageStat> stats = changed ? dbManager.getPackageStats() : null;

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        lastKnownDbCount = totalCount;
                        lastKnownDbMaxId = maxId;
                        if (stats != null) {
                            currentPkgStats = stats;
                        }
                        tvCounter.setText(getString(R.string.saved_count, (int) totalCount, sessionCount));
                        tvDbSize.setText(getString(R.string.db_size, formatBytes(dbBytes)));
                        updateFilterButtonUi();
                        refreshServiceStatusUi();
                        if (changed) {
                            reloadArchiveData(false);
                        }
                    }
                });
            }
        });
    }

    private void reloadArchiveData(final boolean scrollToTop) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        final String pkgFilter = selectedPkgFilter;
        final String keyword = currentKeyword;
        final boolean asc = !newestFirst;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final long[] sig = dbManager.getDbSignature();
                final List<DBManager.PackageStat> stats = dbManager.getPackageStats();
                final List<CaptureRecord> records = dbManager.queryRecords(pkgFilter, keyword, asc);
                final List<CapturePacket> packets = CapturePacket.buildPackets(records, !asc, CapturePacket.DEFAULT_WINDOW_MS);
                final long dbBytes = computeDbFileBytes();
                final int sessionCount = sp.getInt("saved_count", 0);

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        lastKnownDbCount = sig[0];
                        lastKnownDbMaxId = sig[1];
                        currentPkgStats = stats;
                        currentRecords = records;
                        currentPackets = packets;

                        tvCounter.setText(getString(R.string.saved_count, (int) lastKnownDbCount, sessionCount));
                        tvDbSize.setText(getString(R.string.db_size, formatBytes(dbBytes)));
                        updateFilterButtonUi();
                        renderCurrentData(scrollToTop);
                    }
                });
            }
        });
    }

    private void renderCurrentData(boolean scrollToTop) {
        if (currentMode == ArchiveFormatter.MODE_TIMELINE_PACKETS) {
            if (currentPackets.isEmpty() && !currentRecords.isEmpty()) {
                currentPackets = CapturePacket.buildPackets(currentRecords, newestFirst, CapturePacket.DEFAULT_WINDOW_MS);
            }
            adapter.submitData(currentMode, currentPackets, currentRecords);
            tvArchiveSummary.setText(getString(
                    R.string.archive_summary_packets,
                    currentPackets.size(),
                    currentRecords.size(),
                    (int) Math.max(0L, lastKnownDbCount)
            ));
            boolean isEmpty = currentPackets.isEmpty();
            tvEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            rvArchive.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        } else {
            adapter.submitData(currentMode, currentPackets, currentRecords);
            tvArchiveSummary.setText(getString(
                    R.string.archive_summary_records,
                    currentRecords.size(),
                    (int) Math.max(0L, lastKnownDbCount)
            ));
            boolean isEmpty = currentRecords.isEmpty();
            tvEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            rvArchive.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        }

        if (scrollToTop && adapter.getItemCount() > 0) {
            rvArchive.scrollToPosition(0);
        }
    }

    private void confirmAndClearAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_dialog_title)
                .setMessage(R.string.clear_dialog_message)
                .setPositiveButton(R.string.clear_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        executeClearAll();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void executeClearAll() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                dbManager.clearAllData();
                long newClearVersion = sp.getLong("clear_version", 0L) + 1L;
                sp.edit()
                        .putInt("saved_count", 0)
                        .putLong("clear_version", newClearVersion)
                        .apply();
                MyAccessibilityService.notifyDataCleared();
                final long dbBytes = computeDbFileBytes();

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        selectedPkgFilter = null;
                        currentKeyword = "";
                        suppressSearchWatcher = true;
                        etSearch.setText("");
                        suppressSearchWatcher = false;

                        lastKnownDbCount = 0L;
                        lastKnownDbMaxId = 0L;
                        currentPkgStats = new ArrayList<>();
                        currentRecords = new ArrayList<>();
                        currentPackets = new ArrayList<>();

                        tvCounter.setText(getString(R.string.saved_count, 0, 0));
                        tvDbSize.setText(getString(R.string.db_size, formatBytes(dbBytes)));
                        updateFilterButtonUi();
                        renderCurrentData(true);
                        Toast.makeText(MainActivity.this, R.string.clear_done_toast, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void exportAllArchiveText() {
        Toast.makeText(this, R.string.export_working_toast, Toast.LENGTH_SHORT).show();
        final int mode = currentMode;
        final boolean isNewestFirst = newestFirst;
        final String pkgFilter = selectedPkgFilter;
        final String keyword = currentKeyword;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final List<CaptureRecord> records = dbManager.queryRecords(pkgFilter, keyword, !isNewestFirst);
                if (records.isEmpty()) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, R.string.export_empty_toast, Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }
                String formatted = ArchiveFormatter.formatFullArchive(
                        records, mode, isNewestFirst, appLabelResolver, true
                );
                final ExportHelper.ExportResult result = ExportHelper.exportArchiveText(MainActivity.this, formatted);

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (!result.success) {
                            Toast.makeText(
                                    MainActivity.this,
                                    getString(R.string.export_failed_toast, result.errorMessage != null ? result.errorMessage : "Unknown"),
                                    Toast.LENGTH_LONG
                            ).show();
                            return;
                        }
                        showExportResultDialog(result, records);
                    }
                });
            }
        });
    }

    private void showExportResultDialog(final ExportHelper.ExportResult result, final List<CaptureRecord> records) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        TextView msg = new TextView(this);
        msg.setText(getString(R.string.export_dialog_message, records.size(), result.displayPath));
        msg.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        msg.setTextSize(14f);
        msg.setTextIsSelectable(true);
        container.addView(msg);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.export_dialog_title)
                .setView(container)
                .setNegativeButton(R.string.close, null)
                .create();

        addDialogActionButton(container, getString(R.string.export_action_share), R.color.btn_primary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ExportHelper.shareFile(MainActivity.this, result.shareCacheFile, result.mimeType);
            }
        });

        addDialogActionButton(container, getString(R.string.export_action_pure_txt), R.color.btn_success, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSecondaryExport(new SecondaryExportTask() {
                    @Override
                    public ExportHelper.ExportResult run() {
                        return ExportHelper.exportPureText(MainActivity.this, ArchiveFormatter.formatPureText(records));
                    }
                }, dialog);
            }
        });

        addDialogActionButton(container, getString(R.string.export_action_csv), R.color.btn_slate, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSecondaryExport(new SecondaryExportTask() {
                    @Override
                    public ExportHelper.ExportResult run() {
                        return ExportHelper.exportCsv(MainActivity.this, ArchiveFormatter.formatCsv(records, appLabelResolver));
                    }
                }, dialog);
            }
        });

        addDialogActionButton(container, getString(R.string.export_action_db), R.color.btn_slate, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSecondaryExport(new SecondaryExportTask() {
                    @Override
                    public ExportHelper.ExportResult run() {
                        return ExportHelper.exportDatabaseFile(MainActivity.this, dbManager);
                    }
                }, dialog);
            }
        });

        dialog.show();
    }

    private interface SecondaryExportTask {
        ExportHelper.ExportResult run();
    }

    private void runSecondaryExport(final SecondaryExportTask task, final AlertDialog parentDialog) {
        Toast.makeText(this, R.string.export_working_toast, Toast.LENGTH_SHORT).show();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final ExportHelper.ExportResult res = task.run();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (!res.success) {
                            Toast.makeText(
                                    MainActivity.this,
                                    getString(R.string.export_failed_toast, res.errorMessage != null ? res.errorMessage : "Unknown"),
                                    Toast.LENGTH_LONG
                            ).show();
                            return;
                        }
                        if (parentDialog != null && parentDialog.isShowing()) {
                            parentDialog.dismiss();
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.export_dialog_title)
                                .setMessage(res.displayPath)
                                .setPositiveButton(R.string.export_action_share, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        ExportHelper.shareFile(MainActivity.this, res.shareCacheFile, res.mimeType);
                                    }
                                })
                                .setNegativeButton(R.string.close, null)
                                .show();
                    }
                });
            }
        });
    }

    private void addDialogActionButton(LinearLayout parent, String title, int colorRes, View.OnClickListener listener) {
        MaterialButton btn = new MaterialButton(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        btn.setLayoutParams(lp);
        btn.setText(title);
        btn.setAllCaps(false);
        btn.setTextColor(ContextCompat.getColor(this, R.color.white));
        btn.setBackgroundColor(ContextCompat.getColor(this, colorRes));
        btn.setOnClickListener(listener);
        parent.addView(btn);
    }

    private void copyAllArchive(final boolean pureTextOnly) {
        final int mode = currentMode;
        final boolean isNewestFirst = newestFirst;
        final String pkgFilter = selectedPkgFilter;
        final String keyword = currentKeyword;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final List<CaptureRecord> records = dbManager.queryRecords(pkgFilter, keyword, !isNewestFirst);
                if (records.isEmpty()) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, R.string.copy_empty_toast, Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }

                final String fullText = pureTextOnly
                        ? ArchiveFormatter.formatPureText(records)
                        : ArchiveFormatter.formatFullArchive(records, mode, isNewestFirst, appLabelResolver, false);

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        copyToClipboardWithGuard(fullText, pureTextOnly);
                    }
                });
            }
        });
    }

    private void copySingleItem(String text, boolean isFullMetadata) {
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, R.string.copy_empty_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.length() > CLIPBOARD_SAFE_MAX_CHARS) {
            copyToClipboardWithGuard(text, false);
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("TextGrabber", text));
                Toast.makeText(
                        this,
                        isFullMetadata ? R.string.copy_item_full_done : R.string.copy_item_done,
                        Toast.LENGTH_SHORT
                ).show();
            }
        } catch (RuntimeException e) {
            copyToClipboardWithGuard(text, false);
        }
    }

    private void copyToClipboardWithGuard(final String fullText, final boolean pureTextOnly) {
        if (fullText == null || fullText.isEmpty()) {
            Toast.makeText(this, R.string.copy_empty_toast, Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (fullText.length() > CLIPBOARD_SAFE_MAX_CHARS) {
            String clipped = fullText.substring(0, CLIPBOARD_SAFE_MAX_CHARS);
            try {
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("TextGrabber", clipped));
                }
            } catch (RuntimeException ignored) {
            }
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    final ExportHelper.ExportResult res = pureTextOnly
                            ? ExportHelper.exportPureText(MainActivity.this, fullText)
                            : ExportHelper.exportArchiveText(MainActivity.this, fullText);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(
                                    MainActivity.this,
                                    getString(R.string.copy_truncated_auto_export, res.displayPath),
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            });
            return;
        }

        try {
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("TextGrabber", fullText));
            }
            Toast.makeText(
                    this,
                    getString(pureTextOnly ? R.string.copy_pure_done : R.string.copy_all_done, fullText.length()),
                    Toast.LENGTH_SHORT
            ).show();
        } catch (RuntimeException e) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    final ExportHelper.ExportResult res = pureTextOnly
                            ? ExportHelper.exportPureText(MainActivity.this, fullText)
                            : ExportHelper.exportArchiveText(MainActivity.this, fullText);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(
                                    MainActivity.this,
                                    getString(R.string.copy_failed_auto_export, res.displayPath),
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            });
        }
    }

    private void showPackageFilterDialog() {
        final List<DBManager.PackageStat> stats = new ArrayList<>(currentPkgStats);
        int totalCount = 0;
        for (DBManager.PackageStat s : stats) {
            totalCount += s.count;
        }

        final String[] items = new String[stats.size() + 1];
        items[0] = getString(R.string.pkg_filter_all_option, totalCount);
        int checkedIndex = 0;

        for (int i = 0; i < stats.size(); i++) {
            DBManager.PackageStat stat = stats.get(i);
            String label = resolveAppDisplayLabel(stat.pkgName);
            items[i + 1] = label + " (" + stat.count + ")";
            if (selectedPkgFilter != null && selectedPkgFilter.equals(stat.pkgName)) {
                checkedIndex = i + 1;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.pkg_filter_title)
                .setSingleChoiceItems(items, checkedIndex, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            selectedPkgFilter = null;
                        } else {
                            selectedPkgFilter = stats.get(which - 1).pkgName;
                        }
                        updateFilterButtonUi();
                        reloadArchiveData(true);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String resolveAppDisplayLabel(String pkgName) {
        if (pkgName == null || pkgName.trim().isEmpty()) {
            return "未知包名";
        }
        String cached = appLabelCache.get(pkgName);
        if (cached != null) {
            return cached;
        }
        String display = pkgName;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkgName, 0);
            CharSequence appLabel = pm.getApplicationLabel(ai);
            if (appLabel != null && appLabel.length() > 0) {
                String labelStr = appLabel.toString().trim();
                if (!labelStr.isEmpty() && !labelStr.equals(pkgName)) {
                    display = labelStr + " (" + pkgName + ")";
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        } catch (Exception ignored) {
        }
        appLabelCache.put(pkgName, display);
        return display;
    }

    private long computeDbFileBytes() {
        try {
            File dbFile = getDatabasePath(dbManager.getDatabaseName());
            long size = (dbFile != null && dbFile.exists()) ? dbFile.length() : 0L;
            if (dbFile != null) {
                File walFile = new File(dbFile.getAbsolutePath() + "-wal");
                if (walFile.exists()) {
                    size += walFile.length();
                }
            }
            return size;
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute db size", e);
            return 0L;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10((double) bytes) / Math.log10(1024));
        if (digitGroups < 0) digitGroups = 0;
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        double value = bytes / Math.pow(1024, digitGroups);
        return String.format(Locale.US, "%.1f %s", value, units[digitGroups]);
    }

    private boolean isAccessibilitySettingsOn(Context mContext) {
        int accessibilityEnabled = 0;
        final String service = getPackageName() + "/" + MyAccessibilityService.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    mContext.getApplicationContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    mContext.getApplicationContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                splitter.setString(settingValue);
                while (splitter.hasNext()) {
                    String accessibilityService = splitter.next();
                    if (accessibilityService.equalsIgnoreCase(service)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
