package com.studenthaven.android.presentation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import com.studenthaven.android.R;
import com.studenthaven.android.data.StudyDatabase.Flashcard;
import com.studenthaven.android.databinding.FragmentReviewBinding;
import java.util.ArrayList;
import java.util.List;

public final class ReviewDialogFragment extends FullScreenDialogFragment {
    private FragmentReviewBinding binding;
    private StudyViewModel model;
    private int index;
    private boolean answer;
    private boolean submitting;

    public static ReviewDialogFragment create(List<String> ids) {
        ReviewDialogFragment fragment = new ReviewDialogFragment();
        Bundle args = new Bundle();
        args.putStringArrayList("ids", new ArrayList<>(ids));
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentReviewBinding.inflate(inflater, container, false);
        model = new ViewModelProvider(requireActivity()).get(StudyViewModel.class);
        if (savedInstanceState != null) {
            index = savedInstanceState.getInt("index");
            answer = savedInstanceState.getBoolean("answer");
            submitting = savedInstanceState.getBoolean("submitting");
        }
        binding.close.setOnClickListener(view -> dismiss());
        binding.reveal.setOnClickListener(view -> { answer = !answer; render(); });
        binding.card.setOnClickListener(view -> {
            if (!model.operation().getValue().busy()) { answer = !answer; render(); }
        });
        binding.again.setOnClickListener(view -> rate(1));
        binding.hard.setOnClickListener(view -> rate(3));
        binding.good.setOnClickListener(view -> rate(4));
        binding.easy.setOnClickListener(view -> rate(5));
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        model.snapshot().observe(getViewLifecycleOwner(), value -> render());
        model.operation().observe(getViewLifecycleOwner(), operation -> {
            setCancelable(!operation.busy());
            binding.close.setEnabled(!operation.busy());
            binding.reveal.setEnabled(!operation.busy());
            for (int i = 0; i < binding.ratings.getChildCount(); i++) {
                binding.ratings.getChildAt(i).setEnabled(!operation.busy());
            }
            if (submitting && !operation.busy()) {
                submitting = false;
                if (operation.success()) {
                    index++;
                    answer = false;
                } else binding.error.setText(operation.error());
            }
            render();
        });
    }

    private Flashcard current() {
        List<String> ids = requireArguments().getStringArrayList("ids");
        var snapshot = model.snapshot().getValue();
        if (index >= ids.size() || snapshot == null) return null;
        return snapshot.cards().stream().filter(card -> card.id.equals(ids.get(index))).findFirst().orElse(null);
    }

    private void render() {
        if (binding == null) return;
        List<String> ids = requireArguments().getStringArrayList("ids");
        binding.progress.setMax(ids.size());
        binding.progress.setProgress(index);
        binding.progressLabel.setText(getString(R.string.review_progress, index, ids.size()));
        Flashcard card = current();
        boolean complete = index >= ids.size();
        binding.side.setText(complete ? R.string.well_done : answer ? R.string.answer : R.string.question);
        binding.text.setText(complete ? getString(R.string.study_complete)
                : card == null ? getString(R.string.item_missing) : answer ? card.back : card.front);
        binding.card.setBackgroundResource(answer ? R.drawable.bg_answer : R.drawable.bg_question);
        binding.hint.setVisibility(complete || card == null ? View.GONE : View.VISIBLE);
        binding.reveal.setVisibility(!complete && card != null && !answer ? View.VISIBLE : View.GONE);
        binding.ratings.setVisibility(!complete && card != null && answer ? View.VISIBLE : View.GONE);
    }

    private void rate(int quality) {
        Flashcard card = current();
        if (card != null) {
            submitting = true;
            binding.error.setText("");
            model.review(card, quality);
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("index", index);
        outState.putBoolean("answer", answer);
        outState.putBoolean("submitting", submitting);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
