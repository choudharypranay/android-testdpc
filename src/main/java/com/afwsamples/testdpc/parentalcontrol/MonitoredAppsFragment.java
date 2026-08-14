/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.afwsamples.testdpc.parentalcontrol;

import android.app.Fragment;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import com.afwsamples.testdpc.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lets the parent choose which apps the screen time limiter watches.
 *
 * <p>Every installed app is offered, system packages included, because the things worth limiting
 * on a stock phone (Chrome, YouTube) ship as system apps. A filter box and a "selected only" toggle
 * keep a list of that size usable.
 */
public class MonitoredAppsFragment extends Fragment {

  private PackageManager mPackageManager;
  private ScreenTimeStore mStore;

  private final List<AppEntry> mAllApps = new ArrayList<>();
  private final Set<String> mSelected = new HashSet<>();

  private AppAdapter mAdapter;
  private TextView mSummary;
  private CheckBox mShowSelectedOnly;
  private String mFilter = "";

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mPackageManager = getActivity().getPackageManager();
    mStore = new ScreenTimeStore(getActivity());
    mSelected.addAll(mStore.getMonitoredPackages());
    loadApps();
  }

  @Override
  public void onResume() {
    super.onResume();
    getActivity().getActionBar().setTitle(R.string.screen_time_monitored_apps);
  }

  @Override
  public View onCreateView(
      LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.monitored_apps, container, false);

    mSummary = view.findViewById(R.id.selection_summary);
    mShowSelectedOnly = view.findViewById(R.id.show_selected_only);
    ListView listView = view.findViewById(R.id.app_list);

    mAdapter = new AppAdapter();
    listView.setAdapter(mAdapter);
    listView.setOnItemClickListener((parent, itemView, position, id) -> toggle(position));

    EditText filter = view.findViewById(R.id.app_filter);
    filter.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {}

          @Override
          public void afterTextChanged(Editable s) {
            mFilter = s.toString().trim().toLowerCase(Locale.getDefault());
            refresh();
          }
        });

    mShowSelectedOnly.setOnCheckedChangeListener((unused, checked) -> refresh());
    view.findViewById(R.id.clear_selection)
        .setOnClickListener(
            unused -> {
              mSelected.clear();
              persist();
              refresh();
            });

    refresh();
    return view;
  }

  private void loadApps() {
    List<ApplicationInfo> installed =
        mPackageManager.getInstalledApplications(PackageManager.GET_META_DATA);
    String self = getActivity().getPackageName();
    for (ApplicationInfo info : installed) {
      if (self.equals(info.packageName)) {
        // Suspending the controlling app would lock the parent out of the controls.
        continue;
      }
      CharSequence label = mPackageManager.getApplicationLabel(info);
      mAllApps.add(
          new AppEntry(info, label == null ? info.packageName : label.toString()));
    }
    Collections.sort(
        mAllApps,
        new Comparator<AppEntry>() {
          @Override
          public int compare(AppEntry a, AppEntry b) {
            return a.label.compareToIgnoreCase(b.label);
          }
        });
  }

  private void toggle(int position) {
    AppEntry entry = mAdapter.getItem(position);
    if (entry == null) {
      return;
    }
    if (!mSelected.remove(entry.info.packageName)) {
      mSelected.add(entry.info.packageName);
    }
    persist();
    refresh();
  }

  private void persist() {
    mStore.setMonitoredPackages(mSelected);
    // Dropping an app from the list must also lift any block the limiter put on it.
    ParentalControlService.refresh(getActivity());
  }

  private void refresh() {
    List<AppEntry> visible = new ArrayList<>();
    boolean selectedOnly = mShowSelectedOnly != null && mShowSelectedOnly.isChecked();
    for (AppEntry entry : mAllApps) {
      if (selectedOnly && !mSelected.contains(entry.info.packageName)) {
        continue;
      }
      if (!TextUtils.isEmpty(mFilter)
          && !entry.label.toLowerCase(Locale.getDefault()).contains(mFilter)
          && !entry.info.packageName.toLowerCase(Locale.US).contains(mFilter)) {
        continue;
      }
      visible.add(entry);
    }
    mAdapter.clear();
    mAdapter.addAll(visible);
    mAdapter.notifyDataSetChanged();
    mSummary.setText(
        getString(R.string.screen_time_selected_count, mSelected.size(), mAllApps.size()));
  }

  private static final class AppEntry {
    final ApplicationInfo info;
    final String label;

    AppEntry(ApplicationInfo info, String label) {
      this.info = info;
      this.label = label;
    }
  }

  private final class AppAdapter extends ArrayAdapter<AppEntry> {

    AppAdapter() {
      super(getActivity(), R.layout.enable_component_row, new ArrayList<>());
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      View view = convertView;
      if (view == null) {
        view =
            LayoutInflater.from(getContext())
                .inflate(R.layout.enable_component_row, parent, false);
      }
      AppEntry entry = getItem(position);
      if (entry == null) {
        return view;
      }
      ImageView icon = view.findViewById(R.id.pkg_icon);
      try {
        Drawable drawable = mPackageManager.getApplicationIcon(entry.info);
        icon.setImageDrawable(drawable);
      } catch (RuntimeException e) {
        icon.setImageDrawable(null);
      }
      TextView name = view.findViewById(R.id.pkg_name);
      name.setText(entry.label);
      CheckBox checkBox = view.findViewById(R.id.enable_component_checkbox);
      checkBox.setChecked(mSelected.contains(entry.info.packageName));
      checkBox.setOnClickListener(unused -> toggle(position));
      return view;
    }
  }
}
