package com.studenthaven.android.presentation;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import com.studenthaven.android.R;
import com.studenthaven.android.databinding.ActivityMainBinding;

public final class MainActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
        StudyViewModel model = new ViewModelProvider(this).get(StudyViewModel.class);
        View[] tabs = {binding.tabHome, binding.tabClasses, binding.tabNotes,
                binding.tabFlashcards, binding.tabMore};
        for (int i = 0; i < tabs.length; i++) {
            int page = i == 4 ? 7 : i;
            tabs[i].setOnClickListener(view -> model.selectPage(page));
        }
        model.page().observe(this, page -> {
            int selected = page >= 4 ? 4 : page;
            for (int i = 0; i < tabs.length; i++) {
                tabs[i].setSelected(i == selected);
            }
        });
    }
}
