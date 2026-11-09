package com.studenthaven.android.presentation;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatButton;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.studenthaven.android.R;
import com.studenthaven.android.data.StudyDatabase.Assignment;
import com.studenthaven.android.data.StudyDatabase.Course;
import com.studenthaven.android.data.StudyDatabase.Flashcard;
import com.studenthaven.android.data.StudyDatabase.Note;
import com.studenthaven.android.databinding.FragmentStudyBinding;
import com.studenthaven.android.databinding.ItemCardBinding;
import com.studenthaven.android.databinding.ViewEmptyBinding;
import com.studenthaven.android.databinding.ViewStatBinding;
import com.studenthaven.android.domain.CalculateGradeUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

public final class StudyFragment extends Fragment {
    private FragmentStudyBinding binding;
    private StudyViewModel model;
    private StudyViewModel.Snapshot snapshot;
    private int page;
    private int renderedPage = -1;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentStudyBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        model = new ViewModelProvider(requireActivity()).get(StudyViewModel.class);
        androidx.core.view.ViewCompat.setAccessibilityHeading(binding.screenTitle, true);
        binding.search.setText(model.search());
        binding.search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                model.search(s.toString());
                if (page == 2) render();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        binding.course.setOnClickListener(clicked -> chooseCourse());
        binding.add.setOnClickListener(clicked -> addItem());
        binding.collegeChoice.setOnClickListener(clicked -> chooseCollege());
        model.snapshot().observe(getViewLifecycleOwner(), value -> {
            snapshot = value;
            render();
        });
        model.page().observe(getViewLifecycleOwner(), value -> {
            page = value;
            render();
        });
        model.operation().observe(getViewLifecycleOwner(), operation -> {
            binding.status.setText(operation.busy() ? getString(R.string.saving)
                    : operation.error() == null ? "" : operation.error());
            binding.status.setVisibility(operation.busy() || operation.error() != null
                    ? View.VISIBLE : View.GONE);
            render();
        });
    }

    private SharedPreferences profile() {
        return requireContext().getSharedPreferences("profile", 0);
    }

    private void render() {
        if (binding == null) return;
        int[] titles = {R.string.home, R.string.classes, R.string.classroom_notetaking,
                R.string.flashcards, R.string.grades, R.string.profile, R.string.study_groups, R.string.more};
        binding.screenTitle.setText(titles[page]);
        binding.screenTitle.setTextSize(page == 2 ? 28 : 32);
        boolean needsClass = page >= 2 && page <= 4;
        binding.course.setVisibility(needsClass ? View.VISIBLE : View.GONE);
        binding.search.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        binding.add.setVisibility(page >= 1 && page <= 5 ? View.VISIBLE : View.GONE);
        binding.add.setText(page == 5 ? getString(R.string.edit) : getString(R.string.plus));
        binding.add.setTextSize(page == 5 ? 16 : 32);
        binding.add.setContentDescription(getString(page == 5 ? R.string.edit_profile : R.string.add));
        binding.collegeChoice.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        binding.content.removeAllViews();
        binding.content.setGravity(Gravity.TOP);
        if (renderedPage != page) {
            binding.scroll.scrollTo(0, 0);
            renderedPage = page;
        }
        if (snapshot == null || !snapshot.loaded()) {
            section(getString(R.string.loading));
            binding.add.setEnabled(false);
            return;
        }
        binding.add.setEnabled(!model.operation().getValue().busy());
        Course selected = snapshot.courses().stream()
                .filter(course -> course.id.equals(model.selectedCourse())).findFirst().orElse(null);
        if (selected == null && !snapshot.courses().isEmpty()) {
            selected = snapshot.courses().get(0);
            model.selectCourse(selected.id);
        }
        String courseId = selected == null ? "" : selected.id;
        binding.course.setText(selected == null ? getString(R.string.choose_class)
                : getString(R.string.course_label, selected.code, selected.name));
        if (needsClass && selected == null) {
            empty(R.drawable.ic_book, R.string.no_classes, R.string.create_class_first,
                    R.string.add_class, () -> model.selectPage(1));
            binding.add.setEnabled(false);
            return;
        }
        switch (page) {
            case 0 -> renderHome();
            case 1 -> renderClasses();
            case 2 -> renderNotes(courseId);
            case 3 -> renderCards(courseId);
            case 4 -> renderGrades(courseId);
            case 5 -> renderProfile();
            case 6 -> empty(R.drawable.ic_groups, R.string.study_groups, R.string.groups_deferred,
                    R.string.back_to_home, () -> model.selectPage(0));
            case 7 -> {
                card(R.drawable.ic_grades, getString(R.string.grades), "", "", () -> model.selectPage(4));
                card(R.drawable.ic_groups, getString(R.string.study_groups),
                        getString(R.string.cloud_required), "", () -> model.selectPage(6));
                card(R.drawable.ic_profile, getString(R.string.profile), "", "", () -> model.selectPage(5));
            }
            default -> throw new IllegalStateException("Unknown page");
        }
    }

    private void renderHome() {
        String college = profile().getString("college", "");
        if (college.trim().isEmpty()) {
            empty(R.drawable.ic_college, R.string.welcome_haven, R.string.select_college_intro,
                    R.string.choose_college, this::chooseCollege);
            return;
        }
        ItemCardBinding collegeCard = card(R.drawable.ic_college, college,
                getString(R.string.my_college_choice), "", this::chooseCollege);
        collegeCard.title.setTextColor(requireContext().getColor(R.color.purple));
        collegeCard.title.setTextSize(28);
        section(getString(R.string.quick_stats));
        LinearLayout row = statRow();
        stat(row, R.drawable.ic_book, snapshot.courses().size(), R.string.classes, R.color.blue);
        stat(row, R.drawable.ic_note, snapshot.notes().size(), R.string.notes, R.color.green);
        LinearLayout second = statRow();
        int due = (int) snapshot.cards().stream()
                .filter(card -> card.nextReviewAt <= System.currentTimeMillis()).count();
        int pending = (int) snapshot.assignments().stream().filter(item -> item.score == null).count();
        stat(second, R.drawable.ic_cards, due, R.string.cards_due, R.color.orange);
        stat(second, R.drawable.ic_calendar, pending, R.string.ungraded, R.color.purple);
        section(getString(R.string.your_study_space));
        card(R.drawable.ic_note, getString(R.string.classroom_notetaking),
                getString(R.string.note_shortcut), "", () -> model.selectPage(2));
        card(R.drawable.ic_cards, getString(R.string.start_studying),
                getString(R.string.card_shortcut), "", () -> model.selectPage(3));
    }

    private void renderClasses() {
        if (snapshot.courses().isEmpty()) {
            empty(R.drawable.ic_calendar, R.string.no_classes, R.string.classes_intro,
                    R.string.add_class, () -> editCourse(null));
            return;
        }
        section(getString(R.string.all_classes));
        for (Course course : snapshot.courses()) {
            String subtitle = course.code;
            if (!course.professor.trim().isEmpty()) subtitle += "\n" + course.professor;
            if (!course.location.trim().isEmpty()) subtitle += "\n" + course.location;
            int count = (int) snapshot.assignments().stream()
                    .filter(item -> item.courseId.equals(course.id)).count();
            card(R.drawable.ic_book, course.name, subtitle,
                    getResources().getQuantityString(R.plurals.assignment_total, count, count),
                    () -> editCourse(course));
        }
    }

    private void renderNotes(String courseId) {
        String query = model.search().toLowerCase(Locale.ROOT);
        List<Note> notes = snapshot.notes().stream().filter(note -> note.courseId.equals(courseId))
                .filter(note -> (note.title + "\n" + note.content + "\n" + note.tags)
                        .toLowerCase(Locale.ROOT).contains(query)).collect(Collectors.toList());
        if (notes.isEmpty()) {
            empty(R.drawable.ic_note, query.trim().isEmpty() ? R.string.classroom_notetaking : R.string.no_results,
                    query.trim().isEmpty() ? R.string.notes_intro : R.string.no_results_hint,
                    R.string.add_note_for_class, () -> editNote(null));
            return;
        }
        primary(R.string.add_note_for_class, () -> editNote(null));
        for (Note note : notes) {
            ItemCardBinding row = card(0, note.title, note.content,
                    tags(note.tags), () -> editNote(note));
            row.subtitle.setMaxLines(2);
            row.subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.meta.setBackgroundResource(R.drawable.bg_badge);
        }
    }

    private void renderCards(String courseId) {
        List<Flashcard> cards = snapshot.cards().stream()
                .filter(card -> card.courseId.equals(courseId)).collect(Collectors.toList());
        if (cards.isEmpty()) {
            empty(R.drawable.ic_cards, R.string.no_flashcards, R.string.cards_intro,
                    R.string.create_flashcard, () -> editCard(null));
            return;
        }
        card(R.drawable.ic_cards, getString(R.string.start_studying),
                getResources().getQuantityString(R.plurals.cards_ready, cards.size(), cards.size()), "",
                () -> review(cards));
        section(getString(R.string.all_flashcards));
        for (Flashcard flashcard : cards) {
            ItemCardBinding row = card(0, flashcard.front, flashcard.back,
                    flashcard.nextReviewAt <= System.currentTimeMillis() ? getString(R.string.review_due)
                            : getString(R.string.next_review, reviewDate(flashcard.nextReviewAt)),
                    () -> review(List.of(flashcard)));
            row.subtitle.setMaxLines(2);
            row.subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.getRoot().setOnLongClickListener(view -> { editCard(flashcard); return true; });
        }
        primary(R.string.manage_flashcards, () -> {
            String[] names = cards.stream().map(card -> card.front).toArray(String[]::new);
            new AlertDialog.Builder(requireContext()).setTitle(R.string.manage_flashcards)
                    .setItems(names, (dialog, index) -> editCard(cards.get(index))).show();
        });
    }

    private void renderGrades(String courseId) {
        List<Assignment> assignments = snapshot.assignments().stream()
                .filter(item -> item.courseId.equals(courseId)).collect(Collectors.toList());
        CalculateGradeUseCase calculator = new CalculateGradeUseCase();
        OptionalDouble grade = calculator.execute(assignments.stream().map(item ->
                new CalculateGradeUseCase.Mark(item.score, item.maxScore, item.weight))
                .collect(Collectors.toList()));
        section(getString(R.string.your_grades));
        ItemCardBinding summary = card(R.drawable.ic_grades,
                grade.isPresent() ? getString(R.string.grade_percent, grade.getAsDouble())
                        : getString(R.string.no_grade_value),
                getString(R.string.current_grade), grade.isPresent()
                        ? calculator.letter(grade.getAsDouble()) : getString(R.string.no_grade), null);
        summary.title.setTextSize(36);
        summary.title.setId(R.id.summary);
        summary.meta.setTextSize(24);
        section(getString(R.string.assignments));
        if (assignments.isEmpty()) {
            card(R.drawable.ic_calendar, getString(R.string.empty_assignments),
                    getString(R.string.assignments_intro), "", () -> editAssignment(null));
        }
        for (Assignment assignment : assignments) {
            card(R.drawable.ic_calendar, assignment.name,
                    assignment.score == null ? getString(R.string.ungraded)
                            : getString(R.string.score_value, assignment.score, assignment.maxScore),
                    getString(R.string.assignment_detail, assignment.dueDate, assignment.weight),
                    () -> editAssignment(assignment));
        }
    }

    private void renderProfile() {
        String name = profile().getString("name", getString(R.string.student));
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, dp(20), 0, dp(28));
        TextView avatar = new TextView(requireContext());
        String initials = java.util.Arrays.stream(name.trim().split("\\s+"))
                .filter(part -> !part.isEmpty()).limit(2)
                .map(part -> part.substring(0, part.offsetByCodePoints(0, 1)))
                .collect(Collectors.joining()).toUpperCase(Locale.ROOT);
        avatar.setText(initials);
        avatar.setTextSize(40);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        header.addView(avatar, new LinearLayout.LayoutParams(dp(100), dp(100)));
        TextView user = text(name, 24, R.color.primary_text);
        user.setGravity(Gravity.CENTER);
        user.setTypeface(null, android.graphics.Typeface.BOLD);
        user.setPadding(0, dp(16), 0, dp(8));
        header.addView(user);
        TextView college = text(profile().getString("college", getString(R.string.college_unset)),
                13, R.color.purple);
        college.setBackgroundResource(R.drawable.bg_badge);
        college.setGravity(Gravity.CENTER);
        header.addView(college, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        binding.content.addView(header);
        section(getString(R.string.your_statistics));
        LinearLayout stats = statRow();
        stat(stats, R.drawable.ic_book, snapshot.courses().size(), R.string.classes, R.color.blue);
        stat(stats, R.drawable.ic_note, snapshot.notes().size(), R.string.notes, R.color.green);
        stat(stats, R.drawable.ic_cards, snapshot.cards().size(), R.string.flashcards, R.color.orange);
        section(getString(R.string.on_this_device));
        card(R.drawable.ic_profile, getString(R.string.edit_profile), "", "", this::editProfile);
        card(R.drawable.ic_college, getString(R.string.my_college_choice),
                profile().getString("college", getString(R.string.college_unset)), "", this::chooseCollege);
        section(getString(R.string.storage_and_privacy));
        card(R.drawable.ic_book, getString(R.string.local_only), getString(R.string.cloud_notice), "", null);
    }

    private void chooseCourse() {
        if (snapshot == null || snapshot.courses().isEmpty()) {
            model.selectPage(1);
            return;
        }
        String[] names = snapshot.courses().stream()
                .map(course -> getString(R.string.course_label, course.code, course.name)).toArray(String[]::new);
        new AlertDialog.Builder(requireContext()).setTitle(R.string.choose_class)
                .setItems(names, (dialog, index) -> {
                    model.selectCourse(snapshot.courses().get(index).id);
                    render();
                }).show();
    }

    private void empty(int icon, int title, int subtitle, int action, Runnable onClick) {
        binding.content.setGravity(Gravity.CENTER);
        ViewEmptyBinding empty = ViewEmptyBinding.inflate(getLayoutInflater(), binding.content, false);
        empty.icon.setImageResource(icon);
        empty.title.setText(title);
        empty.subtitle.setText(subtitle);
        empty.action.setText(action);
        empty.action.setOnClickListener(view -> onClick.run());
        if (title == R.string.no_classes || title == R.string.no_flashcards) {
            empty.action.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(R.color.navy)));
            empty.action.setTextColor(requireContext().getColor(R.color.pink));
        }
        binding.content.addView(empty.getRoot());
    }

    private ItemCardBinding card(int icon, String title, String subtitle, String meta, Runnable action) {
        ItemCardBinding card = ItemCardBinding.inflate(getLayoutInflater(), binding.content, false);
        card.title.setText(title);
        card.subtitle.setText(subtitle);
        card.subtitle.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        card.meta.setText(meta);
        card.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        card.icon.setVisibility(icon == 0 ? View.GONE : View.VISIBLE);
        if (icon != 0) card.icon.setImageResource(icon);
        card.chevron.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action != null) {
            card.getRoot().setOnClickListener(view -> {
                if (!model.operation().getValue().busy()) action.run();
            });
            card.getRoot().setFocusable(true);
            card.getRoot().setBackgroundResource(R.drawable.bg_card);
        }
        binding.content.addView(card.getRoot());
        return card;
    }

    private void section(String title) {
        TextView text = text(title, 20, R.color.primary_text);
        text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setPadding(0, dp(12), 0, dp(16));
        androidx.core.view.ViewCompat.setAccessibilityHeading(text, true);
        binding.content.addView(text);
    }

    private LinearLayout statRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        binding.content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void stat(LinearLayout row, int icon, int count, int label, int color) {
        ViewStatBinding stat = ViewStatBinding.inflate(getLayoutInflater(), row, false);
        stat.icon.setImageResource(icon);
        stat.icon.setImageTintList(android.content.res.ColorStateList.valueOf(requireContext().getColor(color)));
        stat.value.setText(getString(R.string.stat_value, count));
        stat.label.setText(label);
        row.addView(stat.getRoot());
    }

    private void primary(int label, Runnable action) {
        Button button = new AppCompatButton(requireContext(), null, 0);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTextColor(requireContext().getColor(R.color.primary_text));
        button.setBackgroundResource(R.drawable.bg_primary);
        button.setPadding(dp(20), dp(12), dp(20), dp(12));
        button.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(20);
        binding.content.addView(button, params);
        button.setOnClickListener(view -> action.run());
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(requireContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(requireContext().getColor(color));
        return text;
    }

    private void addItem() {
        switch (page) {
            case 1 -> editCourse(null);
            case 2 -> editNote(null);
            case 3 -> editCard(null);
            case 4 -> editAssignment(null);
            case 5 -> editProfile();
            default -> throw new IllegalStateException("This page has no editor");
        }
    }

    private void editProfile() {
        editor(5, null, new int[]{R.string.display_name, R.string.college},
                new String[]{profile().getString("name", ""), profile().getString("college", "")});
    }

    private void chooseCollege() {
        editor(6, null, new int[]{R.string.college},
                new String[]{profile().getString("college", "")});
    }

    private void editCourse(Course course) {
        editor(1, course == null ? null : course.id,
                new int[]{R.string.course_name, R.string.course_code, R.string.professor, R.string.location},
                course == null ? new String[]{"", "", "", ""}
                        : new String[]{course.name, course.code, course.professor, course.location});
    }

    private void editNote(Note note) {
        editor(2, note == null ? null : note.id,
                new int[]{R.string.title, R.string.content, R.string.tags},
                note == null ? new String[]{"", "", ""}
                        : new String[]{note.title, note.content, note.tags});
    }

    private void editCard(Flashcard card) {
        editor(3, card == null ? null : card.id, new int[]{R.string.question, R.string.answer},
                card == null ? new String[]{"", ""} : new String[]{card.front, card.back});
    }

    private void editAssignment(Assignment value) {
        editor(4, value == null ? null : value.id,
                new int[]{R.string.assignment_name, R.string.due_date, R.string.score,
                        R.string.max_score, R.string.weight},
                value == null ? new String[]{"", LocalDate.now().toString(), "", "100", "1"}
                        : new String[]{value.name, value.dueDate, value.score == null ? "" : value.score.toString(),
                        Double.toString(value.maxScore), Double.toString(value.weight)});
    }

    private void editor(int kind, String id, int[] labels, String[] values) {
        if (!model.operation().getValue().busy()
                && getParentFragmentManager().findFragmentByTag("editor") == null) {
            EditDialogFragment.create(kind, id, model.selectedCourse(), labels, values)
                    .show(getParentFragmentManager(), "editor");
        }
    }

    private void review(List<Flashcard> cards) {
        if (!model.operation().getValue().busy()
                && getParentFragmentManager().findFragmentByTag("review") == null) {
            ReviewDialogFragment.create(cards.stream().map(card -> card.id).collect(Collectors.toList()))
                    .show(getParentFragmentManager(), "review");
        }
    }

    private String tags(String tags) {
        return java.util.Arrays.stream(tags.split(",")).map(String::trim).filter(tag -> !tag.isEmpty())
                .limit(5).map(tag -> "#" + tag).collect(Collectors.joining("   "));
    }

    private String reviewDate(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        renderedPage = -1;
    }
}
