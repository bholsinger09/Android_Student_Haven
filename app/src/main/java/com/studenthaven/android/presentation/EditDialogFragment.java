package com.studenthaven.android.presentation;

import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import com.studenthaven.android.R;
import com.studenthaven.android.data.StudyDatabase.Flashcard;
import com.studenthaven.android.databinding.FragmentEditorBinding;
import java.util.ArrayList;
import java.util.List;

public final class EditDialogFragment extends FullScreenDialogFragment {
    private final List<EditText> fields = new ArrayList<>();
    private StudyViewModel model;
    private FragmentEditorBinding binding;
    private boolean submitting;

    public static EditDialogFragment create(int kind, String id, String courseId,
                                             int[] labels, String[] values) {
        EditDialogFragment fragment = new EditDialogFragment();
        Bundle args = new Bundle();
        args.putInt("kind", kind);
        args.putString("id", id);
        args.putString("courseId", courseId);
        args.putIntArray("labels", labels);
        args.putStringArray("values", values);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentEditorBinding.inflate(inflater, container, false);
        model = new ViewModelProvider(requireActivity()).get(StudyViewModel.class);
        submitting = savedInstanceState != null && savedInstanceState.getBoolean("submitting");
        Bundle args = requireArguments();
        int kind = args.getInt("kind");
        boolean existing = args.getString("id") != null;
        int[] titles = {0, existing ? R.string.edit_class : R.string.add_class,
                existing ? R.string.edit_note : R.string.new_note,
                existing ? R.string.edit_flashcard : R.string.create_flashcard,
                existing ? R.string.edit_assignment : R.string.add_assignment, R.string.edit_profile,
                R.string.choose_your_college};
        binding.editorTitle.setText(titles[kind]);
        androidx.core.view.ViewCompat.setAccessibilityHeading(binding.editorTitle, true);
        var snapshot = model.snapshot().getValue();
        String context = getString(kind == 1 ? R.string.class_information : R.string.local_mode);
        if (snapshot != null && kind >= 2 && kind <= 4) {
            context = snapshot.courses().stream()
                    .filter(course -> course.id.equals(args.getString("courseId")))
                    .map(course -> course.name).findFirst().orElse(context);
        }
        binding.contextLabel.setText(context);
        int[] labels = args.getIntArray("labels");
        String[] values = args.getStringArray("values");
        fields.clear();
        for (int i = 0; i < labels.length; i++) {
            TextView label = new TextView(requireContext());
            label.setText(labels[i]);
            label.setTextSize(13);
            label.setTextColor(requireContext().getColor(R.color.secondary_text));
            label.setPadding(4, dp(12), 0, dp(8));
            EditText field = new EditText(requireContext());
            field.setId(1000 + i);
            label.setLabelFor(field.getId());
            field.setText(values[i]);
            field.setHint(labels[i]);
            field.setTextSize(17);
            field.setTextColor(requireContext().getColor(R.color.primary_text));
            field.setHintTextColor(requireContext().getColor(R.color.secondary_text));
            field.setPadding(dp(16), dp(12), dp(16), dp(12));
            field.setBackgroundResource(R.drawable.bg_card);
            field.setMinHeight(dp(52));
            if (labels[i] == R.string.content) {
                field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                field.setGravity(android.view.Gravity.TOP);
                field.setMinLines(9);
            } else if (labels[i] == R.string.score || labels[i] == R.string.max_score
                    || labels[i] == R.string.weight) {
                field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
            } else {
                field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            }
            binding.fields.addView(label);
            binding.fields.addView(field, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            fields.add(field);
        }
        if (kind == 6) {
            binding.contextLabel.setText(R.string.college_picker_hint);
            LinearLayout choices = new LinearLayout(requireContext());
            choices.setOrientation(LinearLayout.VERTICAL);
            binding.fields.addView(choices);
            EditText field = fields.get(0);
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    showColleges(choices, field, s.toString());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            showColleges(choices, field, "");
        }
        binding.delete.setVisibility(existing ? View.VISIBLE : View.GONE);
        binding.generate.setVisibility(kind == 2 && existing ? View.VISIBLE : View.GONE);
        binding.generate.setOnClickListener(view -> {
            submitting = true;
            model.saveNoteAndGenerate(args.getString("id"), args.getString("courseId"),
                    value(0), value(1), value(2));
        });
        binding.delete.setOnClickListener(view -> new AlertDialog.Builder(requireContext())
                .setMessage(kind == 1 ? R.string.delete_course_warning : R.string.delete_item_warning)
                .setPositiveButton(R.string.delete, (dialog, which) -> delete())
                .setNegativeButton(R.string.cancel, null).show());
        binding.save.setOnClickListener(view -> submit());
        binding.cancel.setOnClickListener(view -> dismiss());
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        model.operation().observe(getViewLifecycleOwner(), operation -> {
            binding.save.setEnabled(!operation.busy());
            binding.cancel.setEnabled(!operation.busy());
            binding.delete.setEnabled(!operation.busy());
            binding.generate.setEnabled(!operation.busy());
            setCancelable(!operation.busy());
            if (submitting && !operation.busy()) {
                if (operation.success()) dismiss();
                else {
                    binding.error.setText(operation.error());
                    submitting = false;
                }
            }
        });
    }

    private void submit() {
        Bundle args = requireArguments();
        String id = args.getString("id");
        String courseId = args.getString("courseId");
        submitting = true;
        switch (args.getInt("kind")) {
            case 1 -> model.saveCourse(id, value(0), value(1), value(2), value(3));
            case 2 -> model.saveNote(id, courseId, value(0), value(1), value(2));
            case 3 -> {
                Flashcard card = null;
                if (id != null && model.snapshot().getValue() != null) {
                    card = model.snapshot().getValue().cards().stream()
                            .filter(item -> item.id.equals(id)).findFirst().orElse(null);
                }
                if (id != null && card == null) missing();
                else model.saveCard(card, courseId, value(0), value(1));
            }
            case 4 -> model.saveAssignment(id, courseId, value(0), value(1),
                    value(2), value(3), value(4));
            case 5 -> model.saveProfile(value(0), value(1));
            case 6 -> model.saveCollege(value(0));
            default -> throw new IllegalStateException("Unknown editor type");
        }
    }

    private void delete() {
        var snapshot = model.snapshot().getValue();
        if (snapshot == null) { missing(); return; }
        String id = requireArguments().getString("id");
        submitting = true;
        switch (requireArguments().getInt("kind")) {
            case 1 -> {
                var item = snapshot.courses().stream().filter(value -> value.id.equals(id)).findFirst().orElse(null);
                if (item == null) missing(); else model.deleteCourse(item);
            }
            case 2 -> {
                var item = snapshot.notes().stream().filter(value -> value.id.equals(id)).findFirst().orElse(null);
                if (item == null) missing(); else model.deleteNote(item);
            }
            case 3 -> {
                var item = snapshot.cards().stream().filter(value -> value.id.equals(id)).findFirst().orElse(null);
                if (item == null) missing(); else model.deleteCard(item);
            }
            case 4 -> {
                var item = snapshot.assignments().stream().filter(value -> value.id.equals(id)).findFirst().orElse(null);
                if (item == null) missing(); else model.deleteAssignment(item);
            }
            default -> throw new IllegalStateException("Unknown editor type");
        }
    }

    private void missing() {
        binding.error.setText(R.string.item_missing);
        submitting = false;
    }

    private void showColleges(LinearLayout choices, EditText field, String query) {
        choices.removeAllViews();
        for (String college : getResources().getStringArray(R.array.colleges)) {
            if (!college.toLowerCase(java.util.Locale.ROOT)
                    .contains(query.toLowerCase(java.util.Locale.ROOT))) continue;
            TextView choice = new TextView(requireContext());
            choice.setText(college);
            choice.setTextSize(17);
            choice.setPadding(dp(16), dp(20), dp(16), dp(20));
            choice.setMinHeight(dp(52));
            choice.setTextColor(requireContext().getColor(R.color.primary_text));
            choice.setBackgroundResource(R.drawable.bg_card);
            choice.setFocusable(true);
            choice.setOnClickListener(view -> field.setText(college));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(8);
            choices.addView(choice, params);
        }
    }

    private String value(int index) { return fields.get(index).getText().toString(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("submitting", submitting);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        fields.clear();
    }
}
