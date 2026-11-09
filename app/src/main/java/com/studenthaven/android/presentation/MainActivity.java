package com.studenthaven.android.presentation;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import com.studenthaven.android.R;
import com.studenthaven.android.databinding.ActivityMainBinding;

public final class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private AccountViewModel accountModel;
    private StudyViewModel studyModel;
    private String socialCallback;
    private String lastSocialCallback;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
        StudyViewModel model = new ViewModelProvider(this).get(StudyViewModel.class);
        studyModel = model;
        accountModel = new ViewModelProvider(this).get(AccountViewModel.class);
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
        if (savedInstanceState != null) {
            socialCallback = savedInstanceState.getString("socialCallback");
            lastSocialCallback = savedInstanceState.getString("lastSocialCallback");
        }
        readSocialCallback(getIntent());
        accountModel.state().observe(this, state -> {
            renderSession();
            if (!state.busy() && socialCallback != null) {
                String attempt = socialCallback;
                lastSocialCallback = attempt;
                socialCallback = null;
                accountModel.finishSocial(attempt);
            }
        });
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readSocialCallback(intent);
        if (socialCallback != null && !accountModel.state().getValue().busy()) {
            String attempt = socialCallback;
            lastSocialCallback = attempt;
            socialCallback = null;
            accountModel.finishSocial(attempt);
        }
    }

    private void readSocialCallback(android.content.Intent intent) {
        android.net.Uri data = intent.getData();
        if (data == null || !"studenthaven".equals(data.getScheme()) || !"oauth".equals(data.getAuthority())
                || !"/callback".equals(data.getPath()) || data.getFragment() != null
                || data.getQueryParameterNames().size() != 1
                || data.getQueryParameters("attempt").size() != 1) return;
        String attempt = data.getQueryParameter("attempt");
        if (attempt != null && attempt.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            if (!attempt.equals(lastSocialCallback)) socialCallback = attempt;
        }
    }

    @Override protected void onSaveInstanceState(android.os.Bundle outState) {
        if (socialCallback != null) outState.putString("socialCallback", socialCallback);
        if (lastSocialCallback != null) outState.putString("lastSocialCallback", lastSocialCallback);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onStart() {
        super.onStart();
        accountModel.refresh();
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        renderSession();
    }

    private void renderSession() {
        boolean signedIn = accountModel.state().getValue().account() != null;
        binding.navigation.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        binding.navigationSeparator.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (getSupportFragmentManager().isStateSaved()) return;
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.screen);
        if (signedIn ? current instanceof StudyFragment : current instanceof WelcomeFragment) return;
        if (!signedIn) {
            for (Fragment fragment : getSupportFragmentManager().getFragments()) {
                if (fragment instanceof DialogFragment dialog) dialog.dismissNow();
            }
            studyModel.selectPage(0);
            studyModel.selectCourse(null);
            studyModel.search("");
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.screen, signedIn ? new StudyFragment() : new WelcomeFragment())
                .commitNow();
    }
}
