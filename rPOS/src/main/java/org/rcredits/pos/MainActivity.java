/*
 * Copyright (C) 2008 ZXing authors
 * and Common Good Finance (for the modifications)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rcredits.pos;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.rcredits.zxing.client.android.CaptureActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Give the user (usually a cashier) a button to press, to scan rCards at POS.
 * When appropriate, also offer a button to show the customer's balance and a button to undo the last transaction.
 * This activity restarts (and cancels all child processes) before each new scan. See Act.restart().
 * @todo: rework deprecated KeyguardManager lines with WindowManager.FLAG_DISMISS_KEYGUARD (see zxing code for tips)
 */
public final class MainActivity extends Act {
    private final Act act = this;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        KeyguardManager keyguardManager = (KeyguardManager)getSystemService(Activity.KEYGUARD_SERVICE);
        KeyguardManager.KeyguardLock lock = keyguardManager.newKeyguardLock(KEYGUARD_SERVICE);
        lock.disableKeyguard();

        setLayout();

        if (!A.failMessage.equals("")) {
            act.sayError(A.failMessage, null); // show failure message from previous (failed) activity
            A.failMessage = "";
        }

        if (!A.update.equals("")) {
            act.askOk("Okay to update now? (takes a few seconds)", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                    //new UpdateApp().execute(A.update); // download the update in the background
                    act.progress(true);
                    new UpdateApp().execute(A.update);
                }
            });
        }
    }

    /**
     * Do what needs doing upon startup or after signing out.
     */
    private void setLayout() {
        Button signedAs = (Button) findViewById(R.id.signed_as);
        TextView welcome = (TextView) findViewById(R.id.welcome);
        TextView version = (TextView) findViewById(R.id.version);
        if (version != null) version.setText("v. " + A.versionName);

        boolean showUndo = (A.agentCan(A.CAN_REFUND) && !A.lastTx.equals(""));
        boolean showBalance = !A.balance.equals("");
        findViewById(R.id.undo_last).setVisibility(showUndo ? View.VISIBLE : View.GONE);
        findViewById(R.id.show_balance).setVisibility(showBalance ? View.VISIBLE : View.GONE);
        if (!A.agent.equals("")) {
            welcome.setText((showUndo || showBalance) ? "" : "Ready for customers...");
            if (!A.agent.equals(A.xagent) && A.failMessage.equals("")) {
                act.mention("Success! You are now signed in.");
                A.xagent = A.agent;
            }
            signedAs.setText("Agent: " + A.agentName);
        } else {
            welcome.setText(R.string.welcome);
            signedAs.setText(R.string.not_signed_in);
        }

    }


    /**
     * from ldmuniz at http://stackoverflow.com/questions/4967669/android-install-apk-programmatically
     */
    private class UpdateApp extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... urls) {
            try {
                URL url = new URL(urls[0]);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("GET");
                c.setDoOutput(true);
                c.connect();

                String PATH = "/mnt/sdcard/Download/";
                String FILENAME = "update.apk";
                File file = new File(PATH);
                file.mkdirs();
                File outputFile = new File(file, FILENAME);
                if(outputFile.exists()) outputFile.delete();
                FileOutputStream fos = new FileOutputStream(outputFile);
                InputStream is = c.getInputStream();

                byte[] buffer = new byte[1024];
                int len1 = 0;
                while ((len1 = is.read(buffer)) != -1) fos.write(buffer, 0, len1);
                fos.close();
                is.close();

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.fromFile(new File(PATH + FILENAME)), "application/vnd.android.package-archive");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // without this flag android returned a intent error!
                act.startActivity(intent);
                A.update = ""; // don't redo current update
            } catch (Exception e) {
                Log.e("UpdateAPP", "Update failed: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        }

        protected void onPostExecute() {
            act.progress(false);
        }
    }

    /**
     * Start the Capture activity (when the user presses the SCAN button).
     * @param v
     */
    public void doScan(View v) {
        Intent intent = new Intent(this, CaptureActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        startActivity(intent);
    }

    /**
     * Show the last customer's balance (when the user presses the "Balance" button).
     * @param v
     */
    public void doShowBalance(View v) {
        act.sayOk("Customer Balance", A.balance, null);
    }

    /**
     * Undo the last transaction after confirmation (when the user presses the "Undo" button).
     * @param v
     */
    public void doUndo(View v) {
        act.askOk(A.undo, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
                List<NameValuePair> pairs = A.auPair(null, "op", "undo");
                A.auPair(pairs, "tx", A.lastTx);
                act.progress(true); // this progress meter gets turned off in Tx's onPostExecute()
                new Tx().execute(pairs);
            }
        });
    }

    private class Tx extends AsyncTask<List<NameValuePair>, Void, String> {
        @Override
        protected String doInBackground(List<NameValuePair>... pairss) {
            return A.apiGetJson(A.region, pairss[0]);
        }

        @Override
        protected void onPostExecute(String json) {act.afterTx(json);}
    }

    /**
     * Sign the cashier out after confirmation.
     */
    public void doSignOut(View v) {
        if (!A.agent.equals("")) act.askOk("Sign out?", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
                A.agent = A.agentName = A.balance = A.undo = A.lastTx = ""; // sign out
                setLayout();
            }
        });
    }

    /**
     * Just pretend to scan, when using AVD (since the webcam feature doesn't work). For testing, of course.
     * @param v
     *//*
    public void onFakeScan(View v) {
        if (!A.FAKE_SCAN) return;
        try {
            act.onScan(new rCard("HTTP://NEW.RC2.ME/I/" + (A.agent.equals("") ? "ZZD-" : "ZZA.") + "zot"));
        } catch (Exception e) {}
    }*/
}
