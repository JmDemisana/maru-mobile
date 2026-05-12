package io.maru.helper;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppsFragment extends Fragment {
    private final List<AppItem> appItems = buildApps();
    private final Map<String, HelperReleaseManager.ReleaseAssetInfo> releaseAssets = new HashMap<>();

    private AppsAdapter adapter;
    private boolean catalogLoading = true;
    private String catalogError = "";

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        View view = inflater.inflate(R.layout.fragment_apps, container, false);

        RecyclerView recyclerView = view.findViewById(R.id.apps_recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AppsAdapter(appItems);
        recyclerView.setAdapter(adapter);

        loadCatalogReleaseAssets();
        return view;
    }

    private void loadCatalogReleaseAssets() {
        catalogLoading = true;
        catalogError = "";
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        new Thread(() -> {
            try {
                Set<String> assetNames = new HashSet<>();
                for (AppItem item : appItems) {
                    assetNames.add(item.apkName);
                }

                Map<String, HelperReleaseManager.ReleaseAssetInfo> latestAssets =
                    HelperReleaseManager.fetchLatestAssetsByName(assetNames);
                if (!isAdded()) {
                    return;
                }

                requireActivity().runOnUiThread(() -> {
                    releaseAssets.clear();
                    releaseAssets.putAll(latestAssets);
                    catalogLoading = false;
                    catalogError = "";
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            } catch (Exception error) {
                if (!isAdded()) {
                    return;
                }

                requireActivity().runOnUiThread(() -> {
                    catalogLoading = false;
                    catalogError =
                        error.getMessage() == null || error.getMessage().trim().isEmpty()
                            ? "Could not check the latest app files right now."
                            : error.getMessage().trim();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    private List<AppItem> buildApps() {
        List<AppItem> apps = new ArrayList<>();
        apps.add(new AppItem(
            "PhotoServe",
            "Desktop print workstation",
            R.drawable.ic_photoserve,
            "io.maru.photoserve",
            "maru-photoserve.apk"
        ));
        apps.add(new AppItem(
            "Cup-Cupper-Cuppers",
            "Case-picking shell game",
            R.drawable.ic_cup,
            "io.maru.cupcuppercuppers",
            "maru-cupcuppercuppers.apk"
        ));
        apps.add(new AppItem(
            "Dael or No Dael",
            "Deal or No Deal clone",
            R.drawable.ic_dael,
            "io.maru.daelornodael",
            "maru-daelornodael.apk"
        ));
        apps.add(new AppItem(
            "TUP Grade Solver",
            "Score-target calculator",
            R.drawable.ic_grade,
            "io.maru.tupgradesolver",
            "maru-tupgradesolver.apk"
        ));
        apps.add(new AppItem(
            "SchedEdit",
            "Weekly class planner",
            R.drawable.ic_schedule,
            "io.maru.schededit",
            "maru-schededit.apk"
        ));
        return apps;
    }

    static final class AppItem {
        final String name;
        final String desc;
        final int icon;
        final String packageName;
        final String apkName;

        AppItem(String name, String desc, int icon, String packageName, String apkName) {
            this.name = name;
            this.desc = desc;
            this.icon = icon;
            this.packageName = packageName;
            this.apkName = apkName;
        }
    }

    final class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.ViewHolder> {
        private final List<AppItem> items;

        AppsAdapter(List<AppItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater
                .from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppItem item = items.get(position);
            MainActivity activity = (MainActivity) requireActivity();
            HelperReleaseManager.ReleaseAssetInfo assetInfo =
                releaseAssets.get(item.apkName.toLowerCase());
            boolean installed = activity.isPackageInstalled(item.packageName);

            holder.icon.setImageResource(item.icon);
            holder.name.setText(item.name);
            holder.desc.setText(item.desc);

            if (installed) {
                holder.meta.setText("Installed on this phone.");
                holder.actionButton.setText("Open");
                holder.actionButton.setEnabled(true);
                holder.actionButton.setOnClickListener(view -> {
                    boolean opened = activity.openInstalledApp(item.packageName);
                    if (!opened) {
                        Toast.makeText(
                            view.getContext(),
                            item.name + " could not open right now.",
                            Toast.LENGTH_SHORT
                        ).show();
                    }
                });
                return;
            }

            if (assetInfo != null) {
                String versionText = assetInfo.getReleaseVersion();
                String sizeText = assetInfo.getSizeLabel();
                if (!versionText.isEmpty() && !sizeText.isEmpty()) {
                    holder.meta.setText("Latest file " + versionText + "  |  " + sizeText);
                } else if (!versionText.isEmpty()) {
                    holder.meta.setText("Latest file " + versionText);
                } else if (!sizeText.isEmpty()) {
                    holder.meta.setText(sizeText);
                } else {
                    holder.meta.setText("Ready to download.");
                }
                holder.actionButton.setText("Get");
                holder.actionButton.setEnabled(true);
                holder.actionButton.setOnClickListener(view -> {
                    activity.downloadApk(assetInfo.downloadUrl, item.apkName);
                    Toast.makeText(
                        view.getContext(),
                        "Downloading " + item.name + "...",
                        Toast.LENGTH_SHORT
                    ).show();
                });
                return;
            }

            if (catalogLoading) {
                holder.meta.setText("Checking release files...");
                holder.actionButton.setText("Checking...");
                holder.actionButton.setEnabled(false);
                holder.actionButton.setOnClickListener(null);
                return;
            }

            if (!catalogError.isEmpty()) {
                holder.meta.setText(catalogError);
                holder.actionButton.setText("Retry");
                holder.actionButton.setEnabled(true);
                holder.actionButton.setOnClickListener(view -> loadCatalogReleaseAssets());
                return;
            }

            holder.meta.setText("This app file is not in the recent mobile releases right now.");
            holder.actionButton.setText("Unavailable");
            holder.actionButton.setEnabled(false);
            holder.actionButton.setOnClickListener(null);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView desc;
            final TextView meta;
            final Button actionButton;

            ViewHolder(View view) {
                super(view);
                icon = view.findViewById(R.id.app_icon);
                name = view.findViewById(R.id.app_name);
                desc = view.findViewById(R.id.app_desc);
                meta = view.findViewById(R.id.app_meta);
                actionButton = view.findViewById(R.id.download_btn);
            }
        }
    }
}
