package chat.viska.android;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorActivity;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.TextInputLayout;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import chat.viska.commons.reactive.MutableReactiveObject;
import chat.viska.xmpp.Jid;
import chat.viska.xmpp.Session;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import javax.annotation.Nonnull;

/**
 * Login with a new or an existing account.
 *
 * <h2>Accepted {@link android.content.Intent} Extra Data</h2>
 *
 * <ul>
 *   <li>{@link #KEY_IS_ADDING}</li>
 *   <li>{@link #KEY_IS_UPDATING}</li>
 * </ul>
 */
public class LoginActivity extends AccountAuthenticatorActivity {

  private class EmptyTextWatcher implements TextWatcher {

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

    @Override
    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

    @Override
    public void afterTextChanged(Editable editable) {
      button.setEnabled(
          jidEditText.getText().length() != 0 && passwordEditText.getText().length() != 0
      );
    }
  }

  private class XmppServiceBinding implements ServiceConnection {

    @Override
    public void onServiceConnected(final ComponentName name, final IBinder binder) {
      service = ((XmppService.Binder) binder).getService();
      attemptLogin();
    }

    @Override
    public void onServiceDisconnected(final ComponentName name) {
      if (isLoggingIn.getValue()) {
        isLoggingIn.setValue(false);
        passwordTextLayout.setError(getString(R.string.desc_login_canceled_by_system));
      }
    }
  }

  /**
   * Key to a {@link Boolean} indicating the {@link android.content.Intent} is to add a new account.
   */
  public final static String KEY_IS_ADDING = "is-adding";

  /**
   * Key to a {@link Boolean} indicating the {@link android.content.Intent} is to log in using an
   * existing account.
   */
  public final static String KEY_IS_UPDATING = "is-updating";

  private final MutableReactiveObject<Boolean> isLoggingIn = new MutableReactiveObject<>(false);
  private final XmppServiceBinding binding = new XmppServiceBinding();
  private ProgressBar progressBar;
  private EditText passwordEditText;
  private EditText jidEditText;
  private TextInputLayout jidTextLayout;
  private TextInputLayout passwordTextLayout;
  private Button button;

  private Jid jid;
  private XmppService service;

  private void login() {
    try {
      jid = new Jid(jidEditText.getText().toString());
    } catch (Exception ex) {
      this.jidTextLayout.setError(getString(R.string.desc_invalid_jid));
      isLoggingIn.setValue(false);
      return;
    }
    if (service == null) {
      bindService(new Intent(this, XmppService.class), binding, BIND_AUTO_CREATE);
    } else {
      attemptLogin();
    }
  }

  private void attemptLogin() {
    service
        .login(jid, passwordEditText.getText().toString())
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(this::onLoginSucceeded, this::onLoginFailed);
  }

  private void onLoginSucceeded() {
    isLoggingIn.setValue(false);
    final AccountAuthenticatorResponse authResponse = getIntent().getParcelableExtra(
        AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE
    );
    final Account account = new Account(
        jidEditText.getText().toString(),
        getString(R.string.api_account_type)
    );
    final AccountManager manager = AccountManager.get(LoginActivity.this);
    final Bundle bundle = new Bundle();
    bundle.putString(AccountManager.KEY_ACCOUNT_TYPE, getString(R.string.api_account_type));
    bundle.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
    if (getIntent().getBooleanExtra(KEY_IS_ADDING, false)) {
      manager.addAccountExplicitly(account, passwordEditText.getText().toString(), null);
      setAccountAuthenticatorResult(bundle);
    } else if (getIntent().getBooleanExtra(KEY_IS_UPDATING, false)) {
      manager.setPassword(account, passwordEditText.getText().toString());
      setAccountAuthenticatorResult(bundle);
    }
    finish();
  }

  private void onLoginFailed(@Nonnull final Throwable cause) {
    isLoggingIn.setValue(false);
    passwordTextLayout.setError(cause.getLocalizedMessage());
  }

  private void cancel() {
    button.setEnabled(false);
    service
        .getSessions()
        .get(jid).dispose()
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(() -> {
          isLoggingIn.setValue(false);
          button.setEnabled(true);
        });
  }

  protected void onButtonClicked(final View view) {
    if (isLoggingIn.getValue()) {
      cancel();
    } else {
      login();
    }
  }

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_login);

    this.jidEditText = findViewById(R.id.login_EditText_jid);
    this.jidTextLayout = findViewById(R.id.login_TextInputLayout_jid);
    this.passwordEditText = findViewById(R.id.login_EditText_password);
    this.passwordTextLayout = findViewById(R.id.login_TextInputLayout_password);
    this.progressBar = findViewById(R.id.login_progress);
    this.button = findViewById(R.id.login_button);

    this.isLoggingIn.getStream().filter(Boolean::booleanValue).subscribe(it -> {
      progressBar.setVisibility(View.VISIBLE);
      button.setText(R.string.title_cancel);
      jidTextLayout.setError(null);
      passwordTextLayout.setError(null);
      jidEditText.setEnabled(false);
      passwordEditText.setEnabled(false);
    });
    this.isLoggingIn.getStream().filter(it -> !it).subscribe(it -> {
      progressBar.setVisibility(View.GONE);
      button.setText(R.string.title_login);
      jidEditText.setEnabled(true);
      passwordEditText.setEnabled(true);
    });

    final EmptyTextWatcher watcher = new EmptyTextWatcher();
    this.jidEditText.addTextChangedListener(watcher);
    this.passwordEditText.addTextChangedListener(watcher);

    this.passwordEditText.setOnEditorActionListener((textView, action, keyEvent) -> {
      if (action == EditorInfo.IME_ACTION_GO) {
        login();
        return true;
      } else {
        return false;
      }
    });

    if (getIntent().getBooleanExtra(KEY_IS_UPDATING, false)) {
      jidEditText.setText(getIntent().getStringExtra(AccountManager.KEY_ACCOUNT_NAME));
      jidEditText.setEnabled(false);
    }
  }

  @Override
  protected void onDestroy() {
    this.isLoggingIn.complete();
    unbindService(binding);
    super.onDestroy();
  }
}
