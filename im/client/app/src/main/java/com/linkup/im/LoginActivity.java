package com.linkup.im;

import android.content.Intent;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;

import androidx.appcompat.app.AppCompatActivity;

import com.linkup.im.databinding.ActivityLoginBinding;

import org.json.JSONObject;

public final class LoginActivity extends AppCompatActivity {
    private ActivityLoginBinding binding;
    private LinkUpApp app;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (LinkUpApp) getApplication();
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Ui.edgeToEdge(this, binding.root);
        binding.serverText.setText("正在连接服务器");
        setLoading(true, "连接中…");
        binding.loginButton.setOnClickListener(view -> login());
        binding.passwordInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                login();
                return true;
            }
            return false;
        });
        resolveServer(false);
    }

    private void login() {
        String username = String.valueOf(binding.usernameInput.getText()).trim();
        String password = String.valueOf(binding.passwordInput.getText());
        binding.usernameLayout.setError(username.isEmpty() ? "请输入账号" : null);
        binding.passwordLayout.setError(password.isEmpty() ? "请输入密码" : null);
        if (username.isEmpty() || password.isEmpty()) return;
        setLoading(true, "登录中…");
        resolveServer(true);
    }

    private void resolveServer(boolean continueLogin) {
        app.api().resolveEndpoint(new ApiClient.EndpointCallback() {
            @Override public void onSuccess(String serverUrl) {
                binding.serverText.setText(serverUrl.replaceFirst("^https?://", ""));
                if (!continueLogin && !app.session().token().isEmpty() && app.session().user() != null) {
                    openMain();
                    return;
                }
                if (continueLogin) performLogin();
                else setLoading(false, "登录");
            }

            @Override public void onError(String message) {
                setLoading(false, "重试连接");
                binding.serverText.setText("服务器连接失败");
                binding.errorText.setText(message);
            }
        });
    }

    private void performLogin() {
        String username = String.valueOf(binding.usernameInput.getText()).trim();
        String password = String.valueOf(binding.passwordInput.getText());
        app.api().login(username, password, new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject body) {
                JSONObject userJson = body.optJSONObject("user");
                if (userJson == null) {
                    onError("服务器响应缺少用户信息");
                    return;
                }
                Models.User user = Models.User.from(userJson);
                app.session().save(body.optString("token"), user);
                app.realtime().connect();
                openMain();
            }

            @Override public void onError(String message) {
                setLoading(false, "登录");
                binding.errorText.setText(message);
            }
        });
    }

    private void setLoading(boolean loading, String buttonText) {
        binding.loginButton.setEnabled(!loading);
        binding.usernameInput.setEnabled(!loading);
        binding.passwordInput.setEnabled(!loading);
        binding.loginButton.setText(buttonText);
        if (loading) binding.errorText.setText("");
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
