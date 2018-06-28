package com.camtech.android.tweetbot.fragments;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.camtech.android.tweetbot.R;
import com.camtech.android.tweetbot.activities.SettingsActivity;
import com.camtech.android.tweetbot.services.TwitterService;
import com.camtech.android.tweetbot.utils.ServiceUtils;
import com.camtech.android.tweetbot.utils.TwitterUtils;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.TwitterAuthProvider;
import com.twitter.sdk.android.core.Callback;
import com.twitter.sdk.android.core.Result;
import com.twitter.sdk.android.core.TwitterException;
import com.twitter.sdk.android.core.TwitterSession;
import com.twitter.sdk.android.core.identity.TwitterLoginButton;

import twitter4j.Twitter;

public class SettingsFragment extends PreferenceFragmentCompat implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        Preference.OnPreferenceChangeListener,
        Preference.OnPreferenceClickListener {

    private final String TAG = SettingsFragment.class.getSimpleName();
    private SettingsActivity settingsActivity;
    private TwitterLoginButton twitterLoginButton;
    private AlertDialog loginDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        // Get a reference to the SettingsActivity once this fragment
        // has been attached to a Context object
        settingsActivity = (SettingsActivity) context;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.pref_tweets);
        findPreference(getString(R.string.pref_logout_key)).setOnPreferenceClickListener(this);
        findPreference(getString(R.string.pref_sign_in_key)).setOnPreferenceClickListener(this);
    }


    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {

    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (preference.getKey().equals(getString(R.string.pref_logout_key))) {
            if (TwitterUtils.isUserLoggedIn()) {
                if (ServiceUtils.isServiceRunning(requireContext(), TwitterService.class)) {
                    requireContext().stopService(new Intent(requireContext(), TwitterService.class));
                }
            }
            TwitterUtils.logout(getContext());
            if (settingsActivity.getSupportActionBar() != null) {
                settingsActivity.getSupportActionBar().setSubtitle(getString(R.string.not_logged_in_message));
            }
        } else if (preference.getKey().equals(getString(R.string.pref_sign_in_key))) {
            if (!TwitterUtils.isUserLoggedIn()) {
                showLoginDialog();
            } else {
                Toast.makeText(getContext(), "You're already logged in", Toast.LENGTH_LONG).show();
            }
        }
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (loginDialog != null && loginDialog.isShowing()) loginDialog.dismiss();
        // Let Twitter handle the auth process
        twitterLoginButton.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onStop() {
        super.onStop();
        // Gotta handle them dialog leaks
        if (loginDialog != null) loginDialog.dismiss();
    }

    private void showLoginDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(settingsActivity);
        View view = getLayoutInflater().inflate(R.layout.dialog_login, getView().findViewById(R.id.dialog_layout_root));
        twitterLoginButton = view.findViewById(R.id.twitter_login);
        twitterLoginButton.setCallback(new Callback<TwitterSession>() {
            @Override
            public void success(Result<TwitterSession> result) {
                handleTwitterSession(result.data, settingsActivity);
            }

            @Override
            public void failure(TwitterException exception) {
                Toast.makeText(getContext(), "Error logging in, please try again", Toast.LENGTH_LONG).show();
            }
        });
        builder.setView(view);
        builder.setTitle(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? Html.fromHtml("<font color='#1DA1F2'>Login</font>", Html.FROM_HTML_MODE_LEGACY)
                        : "Login");
        builder.setNegativeButton("CLOSE", (dialog, which) -> dialog.dismiss());
        loginDialog = builder.create();
        loginDialog.show();
    }

    /**
     * Handles authentication with Twitter. Once the process has completed
     * successfully, the access token and access token secret are saved to a shared preference
     * so that various Twitter methods can be called
     */
    public void handleTwitterSession(TwitterSession session, Activity activity) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        SharedPreferences credentialsPref = activity.getSharedPreferences(
                getString(R.string.pref_auth),
                Context.MODE_PRIVATE);

        AuthCredential credential = TwitterAuthProvider.getCredential(
                session.getAuthToken().token,
                session.getAuthToken().secret);

        auth.signInWithCredential(credential).addOnCompleteListener(activity, task -> {
            if (task.isSuccessful()) {
                Log.i(TAG, "Auth: success");
                // Store the access token and token secret in a shared preference so
                // we can access different Twitter methods later
                credentialsPref.edit()
                        .putString(
                                getString(R.string.pref_token),
                                session.getAuthToken().token)
                        .putString(
                                getString(R.string.pref_token_secret),
                                session.getAuthToken().secret)
                        .apply();
                setActionBarSubtitle();
                Toast.makeText(activity, "Successfully logged in", Toast.LENGTH_SHORT).show();
            } else {
                Log.i(TAG, "Auth: failure ", task.getException());
            }
        });
    }

    @SuppressLint("StaticFieldLeak")
    private void setActionBarSubtitle() {
        // Since getting the screen name of the user cannot be done
        // on the main thread, we have to do so in an AsyncTask
        new AsyncTask<Void, Void, String>() {

            @Override
            protected String doInBackground(Void... voids) {
                Twitter twitter = TwitterUtils.getTwitter(settingsActivity);
                try {
                    return twitter != null ? twitter.getScreenName() : null;
                } catch (twitter4j.TwitterException e) {
                    e.printStackTrace();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String s) {
                super.onPostExecute(s);
                if (s != null) {
                    if (settingsActivity.getSupportActionBar() != null) {
                        settingsActivity
                                .getSupportActionBar()
                                .setSubtitle(getString(R.string.status_user, s));
                    }
                }
            }
        }.execute();
    }
}
