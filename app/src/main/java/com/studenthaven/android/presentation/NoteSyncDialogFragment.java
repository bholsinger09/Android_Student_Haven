package com.studenthaven.android.presentation;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatButton;
import androidx.lifecycle.ViewModelProvider;
import com.studenthaven.android.R;
import com.studenthaven.android.data.ClassSyncDatabase.SyncedClass;
import com.studenthaven.android.data.ClassSyncDatabase.SyncedNote;
import com.studenthaven.android.data.NoteSyncRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

public final class NoteSyncDialogFragment extends FullScreenDialogFragment {
    private NoteSyncViewModel model;
    private LinearLayout root, list, editor;
    private TextView status;
    private EditText title, content, tags;
    private String courseId;
    private boolean filling;
    private final List<View> controls = new ArrayList<>();

    public static NoteSyncDialogFragment forClass(@Nullable String id) {
        NoteSyncDialogFragment fragment = new NoteSyncDialogFragment();
        Bundle args = new Bundle();
        args.putString("courseId", id);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedState) {
        model = new ViewModelProvider(this).get(NoteSyncViewModel.class);
        courseId = requireArguments().getString("courseId");
        controls.clear();
        ScrollView scroll = new ScrollView(requireContext());
        root = column();
        root.setPadding(24, 24, 24, 24);
        scroll.addView(root);
        label(root, getString(courseId == null ? R.string.note_recovery : R.string.synced_notes), 24);
        button(root, R.string.close, this::close);
        button(root, R.string.sync_now, () -> {
            if (model.editorOpen) {
                message(getString(R.string.note_finish_editor));
            } else model.sync();
        });
        if (courseId != null) {
            button(root, R.string.note_recovery, () ->
                    forClass(null).show(getParentFragmentManager(), "note-recovery"));
            button(root, R.string.note_add, () -> {
                if (model.editorOpen) { message(getString(R.string.note_finish_editor)); return; }
                model.editingId = null;
                model.draftTitle = model.draftContent = model.draftTags = "";
                model.editorOpen = true;
                fillEditor();
            });
        }
        status = label(root, "", 14);
        editor = column();
        root.addView(editor);
        title = field(editor, R.string.note_sync_title, 1, value -> model.draftTitle = value);
        content = field(editor, R.string.note_sync_content, 10, value -> model.draftContent = value);
        tags = field(editor, R.string.note_sync_tags, 3, value -> model.draftTags = value);
        button(editor, R.string.save, () -> model.save(courseId));
        button(editor, R.string.cancel, () -> {
            model.editorOpen = false;
            model.editingId = null;
            fillEditor();
        });
        list = column();
        root.addView(list);
        fillEditor();
        return scroll;
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedState) {
        model.state().observe(getViewLifecycleOwner(), this::render);
    }
    @Override public void onStart() {
        super.onStart();
        requireDialog().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    @Override public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        getParentFragmentManager().setFragmentResult("synced-notes-closed", new Bundle());
        super.onDismiss(dialog);
    }

    private void render(NoteSyncViewModel.State state) {
        if (root == null) return;
        boolean enabled = !state.busy() && state.account() != null;
        for (View control : controls) control.setEnabled(enabled);
        setCancelable(!state.busy() && !model.editorOpen);
        status.setText(state.busy() ? getString(R.string.sync_working) : state.message());
        status.setTextColor(requireContext().getColor(state.error() ? R.color.pink : R.color.purple));
        if (!model.editorOpen) editor.setVisibility(View.GONE);
        list.removeAllViews();
        for (SyncedNote item : state.notes()) {
            if (courseId == null ? !item.recovery : !courseId.equals(item.courseId) || item.recovery) continue;
            if (item.deleted && !item.dirty && item.conflict == null && !item.recovery) continue;
            LinearLayout card = column();
            card.setPadding(24, 24, 24, 24);
            card.setBackgroundResource(R.drawable.bg_card);
            list.addView(card);
            label(card, item.title, 20);
            int resource = item.recovery ? R.string.note_recovery_status
                    : item.conflict != null ? R.string.sync_conflict : item.attempt != null ? R.string.sync_retry_pending
                    : item.deleted ? R.string.sync_pending_delete : item.dirty ? R.string.sync_pending : R.string.sync_up_to_date;
            label(card, getString(resource), 14);
            Button view = button(card, R.string.note_view, () -> showVersions(item, false));
            view.setEnabled(enabled);
            if (item.recovery) {
                if (item.recoveredCopyId == null) {
                    button(card, R.string.note_copy_recovery, () -> copy(item, state.classes())).setEnabled(enabled);
                } else label(card, getString(R.string.note_already_copied), 14);
            } else if (item.conflict != null) {
                button(card, R.string.sync_resolve, () -> showVersions(item, true)).setEnabled(enabled);
            } else if (!item.deleted) {
                button(card, R.string.edit, () -> edit(item)).setEnabled(enabled && item.attempt == null);
                button(card, R.string.delete, () -> new AlertDialog.Builder(requireContext())
                        .setMessage(R.string.note_delete_warning)
                        .setPositiveButton(R.string.delete, (dialog, which) -> model.delete(item.id))
                        .setNegativeButton(R.string.cancel, null).show()).setEnabled(enabled && item.attempt == null);
            }
        }
    }

    private void edit(SyncedNote item) {
        if (model.editorOpen) { message(getString(R.string.note_finish_editor)); return; }
        try {
            model.editingId = item.id;
            model.draftTitle = item.title;
            model.draftContent = item.content;
            model.draftTags = NoteSyncRepository.tagText(item.tags);
            model.editorOpen = true;
            fillEditor();
            title.requestFocus();
            title.getParent().requestChildFocus(title, title);
        } catch (JSONException exception) {
            android.util.Log.e("NoteSync", "Invalid saved note tags", exception);
            message(getString(R.string.note_invalid_saved));
        }
    }

    private void showVersions(SyncedNote item, boolean resolve) {
        try {
            String text = getString(R.string.note_local_version) + "\n" + describe(item.title, item.content,
                    item.tags, item.deleted);
            if (item.conflict != null) {
                JSONObject remote = new JSONObject(item.conflict);
                text += "\n\n" + getString(R.string.note_server_version) + "\n"
                        + describe(remote.getString("title"), remote.getString("content"),
                        remote.getJSONArray("tags").toString(), !remote.isNull("deleted_at"));
            }
            ScrollView scroll = new ScrollView(requireContext());
            LinearLayout body = column();
            body.setPadding(24, 24, 24, 24);
            scroll.addView(body);
            TextView detail = label(body, text, 16);
            detail.setTextIsSelectable(true);
            AlertDialog.Builder dialog = new AlertDialog.Builder(requireContext()).setTitle(item.title).setView(scroll);
            if (resolve) {
                dialog.setPositiveButton(R.string.sync_keep_local, (d, w) -> model.resolve(item.id, true))
                        .setNegativeButton(R.string.sync_use_server, (d, w) -> model.resolve(item.id, false))
                        .setNeutralButton(R.string.cancel, null);
            } else dialog.setPositiveButton(R.string.close, null);
            dialog.show();
        } catch (JSONException exception) {
            android.util.Log.e("NoteSync", "Invalid saved note version", exception);
            message(getString(R.string.note_invalid_saved));
        }
    }

    private String describe(String heading, String body, String tagJson, boolean deleted) throws JSONException {
        return (deleted ? getString(R.string.sync_deleted_version) + "\n" : "") + heading + "\n\n"
                + body + "\n\n" + getString(R.string.note_sync_tags) + "\n" + NoteSyncRepository.tagText(tagJson);
    }

    private void copy(SyncedNote item, List<SyncedClass> classes) {
        List<SyncedClass> active = new ArrayList<>();
        for (SyncedClass parent : classes) {
            if (!parent.deleted && parent.conflict == null) active.add(parent);
        }
        if (active.isEmpty()) { message(getString(R.string.note_no_destination)); return; }
        String[] names = new String[active.size()];
        for (int i = 0; i < names.length; i++) names[i] = active.get(i).name + " (" + active.get(i).code + ")";
        new AlertDialog.Builder(requireContext()).setTitle(R.string.note_choose_destination)
                .setItems(names, (dialog, which) -> new AlertDialog.Builder(requireContext())
                        .setMessage(getString(R.string.note_copy_confirmation, names[which]))
                        .setPositiveButton(R.string.sync_copy, (d, w) -> model.copy(item.id, active.get(which).id))
                        .setNegativeButton(R.string.cancel, null).show())
                .setNegativeButton(R.string.cancel, null).show();
    }
    private void fillEditor() {
        filling = true;
        title.setText(model.draftTitle);
        content.setText(model.draftContent);
        tags.setText(model.draftTags);
        editor.setVisibility(model.editorOpen ? View.VISIBLE : View.GONE);
        filling = false;
        setCancelable(!model.editorOpen && !model.state().getValue().busy());
    }
    private void close() {
        if (model.editorOpen) new AlertDialog.Builder(requireContext()).setMessage(R.string.note_discard_warning)
                .setPositiveButton(R.string.close, (d, w) -> dismiss()).setNegativeButton(R.string.cancel, null).show();
        else dismiss();
    }
    private void message(String text) {
        new AlertDialog.Builder(requireContext()).setMessage(text).setPositiveButton(R.string.close, null).show();
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private TextView label(LinearLayout parent, String text, int size) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(size);
        parent.addView(view);
        return view;
    }
    private Button button(LinearLayout parent, int text, Runnable action) {
        Button button = new AppCompatButton(requireContext(), null, 0);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(requireContext().getColor(R.color.purple));
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
        // Dynamic list controls are rebuilt per render; do not retain them across refreshes.
        if (parent == root || parent == editor) controls.add(button);
        return button;
    }
    private EditText field(LinearLayout parent, int hint, int lines, Consumer<String> changed) {
        EditText field = new EditText(requireContext());
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (lines > 1 ? InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        field.setMinLines(lines);
        field.setGravity(android.view.Gravity.TOP);
        field.setSaveEnabled(false);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!filling) changed.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        parent.addView(field);
        controls.add(field);
        return field;
    }
    @Override public void onDestroyView() {
        root = list = editor = null;
        title = content = tags = null;
        controls.clear();
        super.onDestroyView();
    }
}
