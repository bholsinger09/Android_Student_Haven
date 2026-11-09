package com.studenthaven.android.presentation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.studenthaven.android.R;
import com.studenthaven.android.databinding.FragmentWelcomeBinding;

public final class WelcomeFragment extends Fragment {
    private FragmentWelcomeBinding binding;
    private AccountViewModel model;
    private boolean registering;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedState) {
        binding = FragmentWelcomeBinding.inflate(inflater, container, false);
        model = new ViewModelProvider(requireActivity()).get(AccountViewModel.class);
        registering = savedState != null && savedState.getBoolean("registering");
        binding.welcomeSignIn.setOnClickListener(v -> mode(false));
        binding.welcomeCreate.setOnClickListener(v -> mode(true));
        binding.welcomeGoogle.setOnClickListener(v -> model.startSocial("google"));
        binding.welcomeMicrosoft.setOnClickListener(v -> model.startSocial("microsoft"));
        binding.welcomeFinishSocial.setOnClickListener(v -> model.finishSocial(null));
        binding.welcomeCancelSocial.setOnClickListener(v -> model.cancelSocial(false));
        binding.welcomeSubmit.setOnClickListener(v -> {
            androidx.core.view.WindowCompat.getInsetsController(requireActivity().getWindow(), binding.getRoot())
                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime());
            model.signIn(registering, binding.welcomeName.getText().toString(),
                    binding.welcomeEmail.getText().toString(), binding.welcomePassword.getText().toString(),
                    binding.welcomeConfirmation.getText().toString());
        });
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedState) {
        model.state().observe(getViewLifecycleOwner(), this::render);
        model.socialPending().observe(getViewLifecycleOwner(), pending -> render(model.state().getValue()));
        model.browserUrl().observe(getViewLifecycleOwner(), url -> {
            if (url == null) return;
            model.browserOpened();
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE));
            } catch (android.content.ActivityNotFoundException exception) {
                model.cancelSocial(true);
            }
        });
    }

    @Override public void onStart() {
        super.onStart();
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override public void onStop() {
        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onStop();
    }

    private void mode(boolean create) {
        if (registering == create) return;
        registering = create;
        binding.welcomePassword.setText("");
        binding.welcomeConfirmation.setText("");
        render(model.state().getValue());
    }

    private void render(AccountViewModel.State state) {
        if (binding == null) return;
        binding.welcomeHeading.setText(registering ? R.string.account_create : R.string.account_sign_in);
        binding.welcomeSubmit.setText(registering ? R.string.account_create : R.string.account_sign_in);
        binding.welcomeSignIn.setSelected(!registering);
        binding.welcomeCreate.setSelected(registering);
        binding.welcomeName.setVisibility(registering ? View.VISIBLE : View.GONE);
        binding.welcomeConfirmation.setVisibility(registering ? View.VISIBLE : View.GONE);
        binding.welcomeStatus.setText(state.busy() ? getString(R.string.account_working) : state.message());
        binding.welcomeStatus.setTextColor(requireContext().getColor(state.error() ? R.color.pink : R.color.purple));
        boolean pending = Boolean.TRUE.equals(model.socialPending().getValue());
        binding.welcomeFinishSocial.setVisibility(pending ? View.VISIBLE : View.GONE);
        binding.welcomeCancelSocial.setVisibility(pending ? View.VISIBLE : View.GONE);
        for (View control : new View[]{binding.welcomeSignIn, binding.welcomeCreate, binding.welcomeName,
                binding.welcomeEmail, binding.welcomePassword, binding.welcomeConfirmation, binding.welcomeSubmit,
                binding.welcomeFinishSocial, binding.welcomeCancelSocial}) {
            control.setEnabled(!state.busy());
        }
        binding.welcomeGoogle.setEnabled(!state.busy() && !pending);
        binding.welcomeMicrosoft.setEnabled(!state.busy() && !pending);
        if (state.error()) binding.welcomeStatus.post(() -> {
            if (binding != null) binding.welcomeStatus.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, binding.welcomeStatus.getWidth(), binding.welcomeStatus.getHeight()));
        });
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("registering", registering);
    }

    @Override public void onDestroyView() {
        binding.welcomePassword.setText("");
        binding.welcomeConfirmation.setText("");
        binding = null;
        super.onDestroyView();
    }
}
