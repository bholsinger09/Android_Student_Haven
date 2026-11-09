package com.studenthaven.android.presentation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import com.studenthaven.android.R;
import com.studenthaven.android.databinding.FragmentAccountBinding;

public final class AccountDialogFragment extends FullScreenDialogFragment {
    private FragmentAccountBinding binding;
    private AccountViewModel model;
    private boolean registering;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAccountBinding.inflate(inflater, container, false);
        model = new ViewModelProvider(requireActivity()).get(AccountViewModel.class);
        registering = savedInstanceState != null && savedInstanceState.getBoolean("registering");
        binding.accountClose.setOnClickListener(view -> dismiss());
        binding.accountMode.setOnClickListener(view -> {
            registering = !registering;
            binding.accountPassword.setText("");
            binding.accountConfirmation.setText("");
            render(model.state().getValue());
        });
        binding.accountSubmit.setOnClickListener(view -> model.signIn(registering,
                binding.accountName.getText().toString(), binding.accountEmail.getText().toString(),
                binding.accountPassword.getText().toString(), binding.accountConfirmation.getText().toString()));
        binding.accountCheck.setOnClickListener(view -> model.check());
        binding.accountLogout.setOnClickListener(view -> model.logout());
        binding.accountClasses.setOnClickListener(view -> new ClassSyncDialogFragment()
                .show(getChildFragmentManager(), "class-sync"));
        binding.accountForget.setOnClickListener(view -> new AlertDialog.Builder(requireContext())
                .setMessage(R.string.account_forget_warning)
                .setPositiveButton(R.string.account_forget, (dialog, which) -> model.forget())
                .setNegativeButton(R.string.cancel, null).show());
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        model.state().observe(getViewLifecycleOwner(), this::render);
    }

    void refreshAccount() {
        if (model != null) model.refresh();
    }

    @Override public void onStart() {
        super.onStart();
        requireDialog().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void render(AccountViewModel.State state) {
        if (binding == null) return;
        boolean signedIn = state.account() != null;
        binding.accountIdentity.setText(signedIn
                ? getString(R.string.account_identity, state.account().name(), state.account().email())
                : getString(R.string.account_signed_out));
        binding.accountForm.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        binding.accountName.setVisibility(registering ? View.VISIBLE : View.GONE);
        binding.accountConfirmation.setVisibility(registering ? View.VISIBLE : View.GONE);
        binding.accountSubmit.setText(registering ? R.string.account_create : R.string.account_sign_in);
        binding.accountMode.setText(registering ? R.string.account_existing : R.string.account_create);
        binding.accountLogout.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        binding.accountClasses.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        binding.accountClasses.setEnabled(!state.busy());
        binding.accountStatus.setText(state.busy() ? getString(R.string.account_working) : state.message());
        binding.accountStatus.setTextColor(requireContext().getColor(state.error() ? R.color.pink : R.color.purple));
        binding.accountSubmit.setEnabled(!state.busy());
        binding.accountMode.setEnabled(!state.busy());
        binding.accountCheck.setEnabled(!state.busy());
        binding.accountLogout.setEnabled(!state.busy());
        binding.accountForget.setEnabled(!state.busy());
        binding.accountClose.setEnabled(!state.busy());
        binding.accountName.setEnabled(!state.busy());
        binding.accountEmail.setEnabled(!state.busy());
        binding.accountPassword.setEnabled(!state.busy());
        binding.accountConfirmation.setEnabled(!state.busy());
        setCancelable(!state.busy());
        if (signedIn) {
            binding.accountPassword.setText("");
            binding.accountConfirmation.setText("");
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("registering", registering);
    }

    @Override public void onDestroyView() {
        binding.accountPassword.setText("");
        binding.accountConfirmation.setText("");
        binding = null;
        super.onDestroyView();
    }
}
