package com.studenthaven.android.presentation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatButton;
import androidx.lifecycle.ViewModelProvider;
import com.studenthaven.android.R;
import com.studenthaven.android.data.ClassSyncDatabase.SyncedClass;
import com.studenthaven.android.databinding.FragmentClassSyncBinding;
import org.json.JSONException;
import org.json.JSONObject;

public final class ClassSyncDialogFragment extends FullScreenDialogFragment {
    private FragmentClassSyncBinding binding;
    private ClassSyncViewModel model;
    private String editingId;
    private boolean submitting;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentClassSyncBinding.inflate(inflater, container, false);
        model = new ViewModelProvider(this).get(ClassSyncViewModel.class);
        if (savedInstanceState != null) {
            editingId = savedInstanceState.getString("editingId");
            submitting = savedInstanceState.getBoolean("submitting");
        }
        binding.syncClose.setOnClickListener(view -> dismiss());
        getParentFragmentManager().setFragmentResultListener("synced-notes-closed", this,
                (key, result) -> model.reload());
        binding.syncRecovery.setOnClickListener(view -> NoteSyncDialogFragment.forClass(null)
                .show(getParentFragmentManager(), "note-recovery"));
        binding.syncNow.setOnClickListener(view -> model.sync());
        binding.syncImport.setOnClickListener(view -> new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sync_import).setMessage(R.string.sync_import_warning)
                .setPositiveButton(R.string.sync_copy, (dialog, which) -> model.importLocal())
                .setNegativeButton(R.string.cancel, null).show());
        binding.syncSave.setOnClickListener(view -> {
            submitting = true;
            model.save(editingId, binding.syncName.getText().toString(),
                    binding.syncCode.getText().toString(), binding.syncProfessor.getText().toString(),
                    binding.syncLocation.getText().toString());
        });
        binding.syncCancelEdit.setOnClickListener(view -> clearEditor());
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        model.state().observe(getViewLifecycleOwner(), this::render);
    }

    @Override public void onStart() {
        super.onStart();
        requireDialog().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void render(ClassSyncViewModel.State state) {
        if (binding == null) return;
        boolean enabled = !state.busy() && state.account() != null;
        binding.syncIdentity.setText(state.account() == null ? getString(R.string.account_signed_out)
                : state.account().email());
        binding.syncStatus.setText(state.busy() ? getString(R.string.sync_working) : state.message());
        binding.syncStatus.setTextColor(requireContext().getColor(state.error() ? R.color.pink : R.color.purple));
        for (View control : new View[]{binding.syncNow, binding.syncImport, binding.syncSave, binding.syncRecovery,
                binding.syncName, binding.syncCode, binding.syncProfessor, binding.syncLocation,
                binding.syncCancelEdit}) control.setEnabled(enabled);
        binding.syncClose.setEnabled(!state.busy());
        setCancelable(!state.busy());
        if (submitting && !state.busy()) {
            if (!state.error()) clearEditor();
            submitting = false;
        }
        binding.syncEditorTitle.setText(editingId == null ? R.string.add_class : R.string.edit_class);
        binding.syncCancelEdit.setVisibility(editingId == null ? View.GONE : View.VISIBLE);
        binding.syncList.removeAllViews();
        for (SyncedClass item : state.classes()) {
            if (item.deleted && !item.dirty && item.conflict == null) continue;
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(24, 16, 24, 16);
            card.setBackgroundResource(R.drawable.bg_card);
            TextView title = new TextView(requireContext());
            title.setText(getString(R.string.course_label, item.code, item.name));
            title.setTextSize(18);
            card.addView(title);
            TextView details = new TextView(requireContext());
            details.setText(getString(R.string.sync_class_details, item.professor, item.location,
                    getString(item.conflict != null ? R.string.sync_conflict
                    : item.deleted ? R.string.sync_pending_delete
                    : item.attempt != null ? R.string.sync_retry_pending
                    : item.dirty ? R.string.sync_pending : R.string.sync_up_to_date)));
            card.addView(details);
            if (item.conflict != null) {
                action(card, R.string.sync_resolve, enabled, () -> conflict(item));
            } else if (!item.deleted) {
                action(card, R.string.synced_notes, enabled, () -> NoteSyncDialogFragment.forClass(item.id)
                        .show(getParentFragmentManager(), "synced-notes"));
                action(card, R.string.edit, enabled, () -> {
                    editingId = item.id;
                    binding.syncName.setText(item.name);
                    binding.syncCode.setText(item.code);
                    binding.syncProfessor.setText(item.professor);
                    binding.syncLocation.setText(item.location);
                    binding.syncEditorTitle.setText(R.string.edit_class);
                    binding.syncCancelEdit.setVisibility(View.VISIBLE);
                    binding.syncName.requestFocus();
                    binding.syncName.getParent().requestChildFocus(binding.syncName, binding.syncName);
                });
                action(card, R.string.delete, enabled, () -> new AlertDialog.Builder(requireContext())
                        .setMessage(R.string.sync_delete_warning)
                        .setPositiveButton(R.string.delete, (dialog, which) -> model.delete(item.id))
                        .setNegativeButton(R.string.cancel, null).show());
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = 16;
            binding.syncList.addView(card, params);
        }
    }

    private void action(LinearLayout parent, int label, boolean enabled, Runnable callback) {
        Button button = new AppCompatButton(requireContext(), null, 0);
        button.setAllCaps(false);
        button.setTextColor(requireContext().getColor(R.color.purple));
        button.setText(label);
        button.setEnabled(enabled);
        button.setOnClickListener(view -> callback.run());
        parent.addView(button);
    }

    private void conflict(SyncedClass item) {
        try {
            JSONObject remote = new JSONObject(item.conflict);
            String local = item.deleted ? getString(R.string.sync_deleted_version) : describe(
                    item.name, item.code, item.professor, item.location);
            String server = !remote.isNull("deleted_at") ? getString(R.string.sync_deleted_version)
                    : describe(remote.getString("name"), remote.getString("code"),
                    remote.getString("professor"), remote.getString("location"));
            new AlertDialog.Builder(requireContext()).setTitle(R.string.sync_resolve)
                    .setMessage(getString(R.string.sync_compare, local, server))
                    .setPositiveButton(R.string.sync_keep_local, (dialog, which) -> {
                        clearEditor();
                        model.resolve(item.id, true);
                    })
                    .setNegativeButton(R.string.sync_use_server, (dialog, which) -> {
                        clearEditor();
                        model.resolve(item.id, false);
                    })
                    .setNeutralButton(R.string.cancel, null).show();
        } catch (JSONException exception) {
            binding.syncStatus.setText(R.string.sync_bad_conflict);
        }
    }

    private String describe(String name, String code, String professor, String location) {
        return name + "\n" + code + "\n" + professor + "\n" + location;
    }

    private void clearEditor() {
        editingId = null;
        binding.syncName.setText("");
        binding.syncCode.setText("");
        binding.syncProfessor.setText("");
        binding.syncLocation.setText("");
        binding.syncEditorTitle.setText(R.string.add_class);
        binding.syncCancelEdit.setVisibility(View.GONE);
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("editingId", editingId);
        outState.putBoolean("submitting", submitting);
    }

    @Override public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        new ViewModelProvider(requireActivity()).get(AccountViewModel.class).refresh();
    }

    @Override public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
